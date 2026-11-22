---
title: Resource Reading Middleware
sidebar_label: Resource Reading Middleware
description: Use ReadResourceTool and ReadArtifactMiddleware to let an Agent read user-provided images and web links
sidebar_position: 6
displayed_sidebar: sdkSidebar
---

# Resource Reading Middleware

When a user sends an image link or an article link, the model cannot access it by itself. `ReadResourceTool` exposes a model-callable `read_resource` tool that probes resource metadata, and `ReadArtifactMiddleware` collects the result after the tool succeeds, then assembles images or references into a `UserMessage` before the next model call.

It separates two concerns:

```text
Model calls read_resource
        ↓
ReadResourceTool probes http/https metadata (does not download the body)
        ↓
ReadArtifactMiddleware collects results
        ↓
Assembles a UserMessage before the next model call
        ↓
Images use the multimodal channel; pages/text use references
```

## 1. Quick start

### 1.1 Register the reading tool

```java
import cloud.changlu.agentforge.agent.extend.middlewares.read.tool.ReadResourceTool;
import cloud.changlu.agentforge.agent.extend.middlewares.read.tool.ReadToolOptions;
import cloud.changlu.agentforge.agent.tool.local.LocalToolFactory;
import cloud.changlu.agentforge.model.tool.execution.ToolService;
import cloud.changlu.agentforge.model.tool.spec.ToolSpecification;
import cloud.changlu.agentforge.model.tool.ToolExecutor;

import java.util.Collections;
import java.util.Map;

ReadResourceTool readTool = ReadResourceTool.builder()
        .options(ReadToolOptions.builder()
                .maxPaths(5)                      // at most 5 URLs per call
                .maxImageBytes(10 * 1024 * 1024)  // image probe limit: 10 MB
                .timeoutMillis(5000)              // connect and read timeout
                .build())
        .build();

Map<ToolSpecification, ToolExecutor> readTools =
        LocalToolFactory.buildLocalToolsWithThought(
                Collections.<Object>singletonList(readTool), true);

ToolService toolService = new ToolService();
toolService.tools(readTools);
```

### 1.2 Register the reading middleware

```java
import cloud.changlu.agentforge.agent.extend.middlewares.read.middleware.ReadArtifactMiddleware;

ReadArtifactMiddleware readMiddleware = ReadArtifactMiddleware.builder()
        .supportsImage(true)                          // whether the model supports images
        .requiresInlineImage(false)                   // set true for Anthropic
        .maxInlineImageBytes(4L * 1024 * 1024)        // degrade to text above 4 MB
        .dedupeEnabled(true)                          // deduplicate resources in one batch
        .logEnabled(true)
        .build();
```

### 1.3 Build the Agent

```java
ReActAgent agent = ReActAgent.builder()
        .agentName("resource-agent")
        .systemPrompt("When the user provides http/https image or web links, call read_resource first.")
        .chatModel(chatModel)
        .streamingChatModel(streamingChatModel)
        .chatMemoryProvider(new MessageWindowChatMemoryProvider(50))
        .toolService(toolService)
        .middleware(readMiddleware)
        .build();
```

Register both the tool and the middleware:

- Tool only: the model receives metadata JSON, but images never enter the multimodal channel;
- Middleware only: there is no `read_resource` to call, so there is nothing to collect.

## 2. What the tool reads

`read_resource` accepts only `http` / `https` URLs and issues a `HEAD` request for metadata:

| Type | Detection | Follow-up |
| --- | --- | --- |
| Image | `Content-Type: image/*` or a common image extension | Image channel (URL or Base64) |
| Web page | `text/html` | Text reference with an accessible URL |
| Text | `text/*` | Text reference with an accessible URL |
| Other file | Other types or extensions | Text reference with an accessible URL |

Limits:

- at most 5 URLs per call;
- public hosts only: `localhost`, `127.x`, `10.x`, `172.16-31.x`, and `192.168.x` are rejected (SSRF protection);
- only response headers are read; the body is not downloaded, and size comes from `Content-Length`;
- probe timeout defaults to 5 seconds.

> `read_resource` is not a web page text extractor. Web pages and text resources are passed to the model as "accessible URL + metadata". If you need page text extraction, PDF text, or OCR, implement your own reading tool and let `ReadArtifactMiddleware` recognize it (see section 6).

## 3. How images reach the model

The middleware chooses the payload based on model capability and URL reachability:

```text
Does the model support images?
  ├─ No  -> TEXT (reference)
  └─ Yes
      ├─ Not an image -> TEXT
      └─ Image
          ├─ requiresInlineImage=true (e.g. Anthropic) -> INLINE
          ├─ Publicly reachable URL -> URL
          ├─ Image within maxInlineImageBytes -> INLINE
          └─ Otherwise -> TEXT
```

| Payload | Framework behavior | When to use |
| --- | --- | --- |
| `URL` | Pass the external URL to the model | Models that accept external image URLs (e.g. OpenAI) |
| `INLINE` | Download the image and encode it as Base64 | Anthropic, private images, or avoiding external dependencies |
| `TEXT` | Provide name, type, size, and URL only | Non-vision models, oversized images, load failures |

If downloading or Base64 conversion fails, the middleware degrades to a text reference and logs a warning. The conversation continues.

> The default `ReadResourceTool` rejects private and loopback hosts, so it never produces private image URLs. If a custom `ReadResourceFetcher` returns a private URL, the middleware still routes it as `INLINE`.

## 4. Middleware timing

`ReadArtifactMiddleware` implements three key callbacks:

| Callback | Behavior |
| --- | --- |
| `afterToolExecution` | Detects reading tools and queues successful resource metadata for the current run |
| `afterConcurrentToolExecution` | Collects results from concurrent batches so batch reading still works |
| `beforeModelCall` | Assembles queued resources into a `UserMessage`, writes it to memory, and rebuilds the request |

What the model sees:

```text
Turn 1: the model decides to call read_resource
        ↓
The tool returns resource metadata
        ↓
Before the next model call, the middleware appends a user message:
        "Here are the resources read in this turn. Continue with them."
        + images / references
        ↓
Turn 2: the model answers using the attachments
```

The assembled message is written to `ChatMemory`, so later turns still see it.

### Registration order

`beforeModelCall` runs in registration order:

```java
.middlewares(Arrays.asList(
        userMessageAppendMiddleware,
        readMiddleware,
        compactMiddleware,
        loggingMiddleware))
```

Place the reading middleware before context compaction so compaction sees the newly appended resources; place it before logging so the log reflects the final request.

## 5. Custom image loading

The built-in `UrlImageLoader` reads `http/https` images. If your images live on local disk, OSS, S3, or require signed access, implement `ImageLoader`:

```java
public class OssImageLoader implements ImageLoader {

    @Override
    public ImageBinary load(String url) {
        byte[] bytes = ossClient.read(url);
        return new ImageBinary(bytes, "image/png");
    }
}
```

Register it once at startup:

```java
AttachmentContentAssembler.setDefaultImageLoader(new OssImageLoader());
```

Or scope it to one Agent:

```java
ReadArtifactMiddleware.builder()
        .imageLoader(new OssImageLoader())
        .build();
```

## 6. Recognizing custom reading tools

If you already have a reading tool, make its result contain `ReadBatchResult.SCHEMA` so the middleware can recognize it:

```java
ReadArtifactMiddleware.builder()
        .readToolNames("read_resource", "fetch_article")
        .schema(ReadBatchResult.SCHEMA)
        .build();
```

You can also replace the probe implementation with a custom `ReadResourceFetcher`, for example to integrate an internal document service:

```java
ReadResourceTool.builder()
        .fetcher((paths, hint) -> {
            ReadBatchResult batch = new ReadBatchResult();
            // Probe each path and fill ReadResult entries
            return batch;
        })
        .build();
```

Custom implementations should continue to:

- accept only allowed protocols and hosts;
- limit resource count and timeouts;
- return `ReadResult.failure(...)` instead of throwing uncaught exceptions;
- keep the result structure compatible with `ReadResultJson`.

## 7. Reading tools vs. direct attachments

| Scenario | Recommended approach |
| --- | --- |
| User uploads a local file | Read it in business code and pass an `AgentAttachment` via `AgentRequest.attachments` |
| User provides a public image URL | `ReadResourceTool` + `ReadArtifactMiddleware` |
| User provides a private image URL | Build an `AgentAttachment` with `INLINE` and a custom `ImageLoader` |
| Need page text / PDF / OCR | Implement a custom reading tool or an `AttachmentContentExtractor` |

Direct attachments are validated and prepared by your business layer. The reading tool only handles URLs that the model decides to read.

## 8. Boundaries and recommendations

- The tool probes metadata only; it does not extract page or document text;
- Public URL reachability is checked by the server; verify egress networking in restricted environments;
- Inline images increase request size; relax gateway and provider limits accordingly;
- Non-vision models always degrade to text references; do not ask them to describe images;
- Resource reading is on demand: if the model never calls `read_resource`, no attachment is produced;
- In production, log failed URLs and error codes to debug unreachable resources.

Next: learn how to attach text, images, and file references directly: [Agent: Streaming and Non-streaming](../agent).
