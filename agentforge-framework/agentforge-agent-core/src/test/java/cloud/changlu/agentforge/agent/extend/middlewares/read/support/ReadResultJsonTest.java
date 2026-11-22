package cloud.changlu.agentforge.agent.extend.middlewares.read.support;

import cloud.changlu.agentforge.agent.extend.middlewares.read.domain.ReadBatchResult;
import cloud.changlu.agentforge.agent.extend.middlewares.read.domain.ReadResourceKind;
import cloud.changlu.agentforge.agent.extend.middlewares.read.domain.ReadResult;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ReadResultJsonTest {

    @Test
    public void shouldRoundTripBatchResult() {
        ReadResult image = new ReadResult();
        image.setKind(ReadResourceKind.IMAGE);
        image.setUrl("https://example.com/a.png");
        image.setName("a.png");
        image.setMimeType("image/png");
        image.setFileType("png");
        image.setSize(1024L);

        ReadResult link =
                ReadResult.failure("https://example.com/b", "UNREACHABLE", "url is not reachable");

        ReadBatchResult batch = new ReadBatchResult();
        batch.setResults(Arrays.asList(image, link));

        ReadBatchResult parsed = ReadResultJson.parseBatch(ReadResultJson.toJson(batch));

        assertEquals(ReadBatchResult.SCHEMA, parsed.getSchema());
        assertTrue(parsed.isOk());
        assertEquals(2, parsed.getResults().size());
        assertEquals(ReadResourceKind.IMAGE, parsed.getResults().get(0).getKind());
        assertEquals("https://example.com/a.png", parsed.getResults().get(0).getUrl());
        assertEquals(Long.valueOf(1024L), parsed.getResults().get(0).getSize());
        assertEquals("UNREACHABLE", parsed.getResults().get(1).getErrorCode());
    }
}
