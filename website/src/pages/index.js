import React from 'react';
import Layout from '@theme/Layout';
import Link from '@docusaurus/Link';
import Translate from '@docusaurus/Translate';
import styles from './index.module.css';

const capabilityCards = [
  {index: '01', title: 'Unified LLM', text: 'OpenAI、Anthropic 与 OpenAI-compatible 服务共享一套 ChatModel 契约，上层代码不被厂商协议锁定。', id: 'home.card.llm', accent: 'MODEL', to: '/docs/sdk/model'},
  {index: '02', title: 'Agent Runtime', text: 'ReActAgent 围绕 think → act → 记忆写回组织执行，同步与流式共用同一套循环、取消和步长控制。', id: 'home.card.runtime', accent: 'RUNTIME', to: '/docs/sdk/agent'},
  {index: '03', title: 'Tool Calling', text: '本地 Java 方法、HTTP 插件与 MCP 服务统一转换为 Function Call，参数绑定、执行与结果回写一次完成。', id: 'home.card.tool', accent: 'ACTION', to: '/docs/sdk/tool'},
  {index: '04', title: 'Middleware', text: '在模型调用、工具执行与 Loop 生命周期挂载日志、Trace、审计和脱敏逻辑，横切能力与主循环解耦。', id: 'home.card.middleware', accent: 'GOVERNANCE', to: '/docs/sdk/middleware'},
  {index: '05', title: 'Messages & Events', text: '统一消息模型与流式事件，让同步结果、推理增量、工具调用和前端 SSE 收敛到同一套语义。', id: 'home.card.events', accent: 'STREAM', to: '/docs/sdk/message-event'},
  {index: '06', title: 'Service Workbench', text: '模型配置、Agent 调试、版本发布、会话重放与链路追踪开箱即用，直接体验完整 Agent 工作台。', id: 'home.card.service', accent: 'SERVICE', to: '/docs/service/introduction'},
];

const modules = [
  {name: 'agentforge-model-api', text: 'Provider 无关的模型契约与统一 req/vo', textId: 'home.module.api', accent: 'CONTRACT'},
  {name: 'agentforge-model-core', text: '默认参数合并、JDK HTTP 传输与 Tool 执行引擎', textId: 'home.module.core', accent: 'CORE'},
  {name: 'agentforge-model-openai', text: 'OpenAI / OpenAI-compatible Provider', textId: 'home.module.openai', accent: 'PROVIDER'},
  {name: 'agentforge-model-anthropic', text: 'Anthropic Messages Provider', textId: 'home.module.anthropic', accent: 'PROVIDER'},
  {name: 'agentforge-model-registry', text: '按配置装配 ChatModel 的开箱即用工厂', textId: 'home.module.registry', accent: 'FACTORY'},
  {name: 'agentforge-agent-core', text: 'ReAct Agent、Middleware、Memory 与 Tool 扩展', textId: 'home.module.agent', accent: 'RUNTIME'},
];

const layers = [
  {tag: '01', title: 'Model Protocol', text: 'ChatRequest / ChatResponse / Message / Streaming 回调', textId: 'home.layer.model', note: '厂商协议收敛在 Provider 内', noteId: 'home.layer.model.note'},
  {tag: '02', title: 'Tool & Function Call', text: 'Local / HTTP / MCP 统一 ToolSpecification 与执行器', textId: 'home.layer.tool', note: '模型决策变成真实动作', noteId: 'home.layer.tool.note'},
  {tag: '03', title: 'ReAct Agent', text: 'Think → Act → 写回记忆，支持同步、流式、取消与重试', textId: 'home.layer.react', note: '一套内核，两种驱动器', noteId: 'home.layer.react.note'},
  {tag: '04', title: 'Middleware & Trace', text: 'Loop、模型、工具、停止阶段的可组合横切能力', textId: 'home.layer.middleware', note: '可观测、可治理、可扩展', noteId: 'home.layer.middleware.note'},
  {tag: '05', title: 'Harness & Service', text: '权限、审批、沙箱与开箱即用的 Agent 工作台', textId: 'home.layer.service', note: '从框架走向生产', noteId: 'home.layer.service.note'},
];

const quickCode = `ReActAgent agent = ReActAgent.builder()
        .chatModel(chatModel)
        .streamingChatModel(streamingChatModel)
        .chatMemoryProvider(new MessageWindowChatMemoryProvider(50))
        .toolService(toolService)
        .middleware(new LoggingIAgentMiddleware())
        .build();

ChatResult result = agent.run(AgentRequest.builder()
        .memoryId("user-1")
        .question("北京今天天气怎么样？")
        .build());`;

function RuntimePanel() {
  return <div className={styles.runtimePanel} aria-label="AgentForge runtime example">
    <div className={styles.panelBar}><div className={styles.windowDots}><i/><i/><i/></div><span>agent-run.trace</span><b>LIVE</b></div>
    <div className={styles.runtimeBody}>
      <div className={styles.runMeta}><span>RUN / AF-1042</span><span>32ms</span></div>
      <div className={styles.prompt}><span>USER</span><p><Translate id="home.demo.prompt">分析项目结构，并给出下一步实现计划。</Translate></p></div>
      <ol className={styles.trace}>
        <li><span className={styles.stepIcon}>01</span><div><b>THINK</b><p><Translate id="home.demo.think">识别目标与当前上下文</Translate></p></div><em>done</em></li>
        <li><span className={styles.stepIcon}>02</span><div><b>TOOL</b><p>repository.inspect()</p></div><em>done</em></li>
        <li className={styles.activeStep}><span className={styles.stepIcon}>03</span><div><b>ACT</b><p><Translate id="home.demo.act">生成可执行的任务计划</Translate></p></div><em>running</em></li>
      </ol>
      <div className={styles.signal}><span/><span/><span/><span/><span/><span/><span/><span/></div>
    </div>
  </div>;
}

export default function Home() {
  return <Layout title="AgentForge" description="Forge Intelligence into Action.">
    <main className={styles.page}>
      <section className={styles.hero}>
        <div className={styles.ambientGrid}/><div className={styles.glow}/>
        <div className={styles.heroInner}>
          <div className={styles.heroCopy}>
            <div className={styles.release}><span/><Translate id="home.release">AgentForge v1.0 · Java Agent Framework</Translate></div>
            <h1><Translate id="home.title.line1">让智能不止于回答</Translate><br/><span><Translate id="home.title.line2">而是真正行动。</Translate></span></h1>
            <p><Translate id="home.description">从统一 LLM 抽象到 Agent Runtime，AgentForge 为 Java 开发者提供透明、可控、可扩展的智能体工程底座。</Translate></p>
            <div className={styles.actions}>
              <Link className={styles.primaryAction} to="/docs/quickstart/getting-started"><Translate id="home.start">开始构建</Translate><span>→</span></Link>
              <Link className={styles.secondaryAction} to="/docs/service/quick-deploy"><Translate id="home.deploy">本地部署 Service</Translate><span>↗</span></Link>
            </div>
            <div className={styles.heroFacts}><span><b>Java 8+</b> native</span><span><b>OpenAI + Anthropic</b> providers</span><span><b>MIT</b> open source</span></div>
          </div>
          <RuntimePanel/>
        </div>
      </section>

      <section className={styles.flowSection}>
        <div className={styles.sectionIntro}><span>HOW IT WORKS</span><h2><Translate id="home.flow.title">一条清晰的智能执行链路</Translate></h2><p><Translate id="home.flow.desc">每一层都有明确边界，每一步都可以观察、替换与扩展。</Translate></p></div>
        <div className={styles.flow}>
          <div><small>INPUT</small><b>Goal</b><span><Translate id="home.flow.goal">自然语言目标</Translate></span></div><i>→</i>
          <div><small>REASON</small><b>Think</b><span><Translate id="home.flow.think">上下文与规划</Translate></span></div><i>→</i>
          <div><small>EXECUTE</small><b>Tool</b><span><Translate id="home.flow.tool">调用真实能力</Translate></span></div><i>→</i>
          <div className={styles.flowResult}><small>OUTPUT</small><b>Action</b><span><Translate id="home.flow.action">完成任务闭环</Translate></span></div>
        </div>
      </section>

      <section className={styles.capabilities}>
        <div className={styles.sectionIntro}><span>BUILDING BLOCKS</span><h2><Translate id="home.capabilities.title">为 Agent 工程而生</Translate></h2><p><Translate id="home.capabilities.desc">六个核心能力覆盖模型接入、工具调用、执行治理与工作台落地。</Translate></p></div>
        <div className={styles.cardGrid}>{capabilityCards.map(card => <Link className={styles.card} key={card.index} to={card.to}>
          <div className={styles.cardTop}><small>{card.index}</small><span>{card.accent}</span></div><h3>{card.title}</h3><p><Translate id={card.id}>{card.text}</Translate></p><div className={styles.cardLine}/>
        </Link>)}</div>
      </section>

      <section className={styles.stackSection}>
        <div className={styles.sectionIntro}><span>ARCHITECTURE</span><h2><Translate id="home.stack.title">自底向上的 Agent 技术栈</Translate></h2><p><Translate id="home.stack.desc">从协议契约逐层向上组合，任何一层都可以独立替换。</Translate></p></div>
        <div className={styles.layers}>{layers.map(layer => <article key={layer.tag}>
          <span>{layer.tag}</span><div><b>{layer.title}</b><p><Translate id={layer.textId}>{layer.text}</Translate></p></div><small><Translate id={layer.noteId}>{layer.note}</Translate></small>
        </article>)}</div>
      </section>

      <section className={styles.modulesSection}>
        <div className={styles.sectionIntro}><span>MODULES</span><h2><Translate id="home.modules.title">按需引入，不做整包绑定</Translate></h2><p><Translate id="home.modules.desc">统一 BOM 管理版本，从模型契约到 Agent 运行时按需组合。</Translate></p></div>
        <div className={styles.moduleGrid}>{modules.map(module => <article key={module.name}>
          <span>{module.accent}</span><code>{module.name}</code><p><Translate id={module.textId}>{module.text}</Translate></p>
        </article>)}</div>
        <div className={styles.pomBox}>
          <div className={styles.pomHead}><span>pom.xml</span><Link to="/docs/quickstart/getting-started"><Translate id="home.modules.link">查看完整依赖 →</Translate></Link></div>
          <pre><code>{`<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-agent-core</artifactId>
    <version>1.0.0</version>
</dependency>`}</code></pre>
        </div>
      </section>

      <section className={styles.codeSection}>
        <div className={styles.codeCopy}>
          <span>QUICK START</span>
          <h2><Translate id="home.code.title">几十行代码，跑通第一个 Agent</Translate></h2>
          <p><Translate id="home.code.desc">注入模型、记忆与工具，选择同步或流式执行。同一个 ReActAgent 实例可以在两种模式间自由切换。</Translate></p>
          <div className={styles.codeActions}>
            <Link className={styles.primaryAction} to="/docs/quickstart/getting-started"><Translate id="home.start">开始构建</Translate><span>→</span></Link>
            <Link className={styles.textAction} to="/docs/sdk/agent"><Translate id="home.code.detail">阅读 Agent 设计</Translate><span>↗</span></Link>
          </div>
        </div>
        <div className={styles.codePanel}>
          <div className={styles.panelBar}><div className={styles.windowDots}><i/><i/><i/></div><span>FirstAgent.java</span><b>JAVA</b></div>
          <pre><code>{quickCode}</code></pre>
        </div>
      </section>

      <section className={styles.serviceSection}>
        <div>
          <span>SERVICE WORKBENCH</span>
          <h2><Translate id="home.service.title">不想从零搭前端？直接启动工作台</Translate></h2>
          <p><Translate id="home.service.desc">AgentForge Service 提供模型配置、Agent 调试、版本发布、会话重放与链路追踪，一条 Docker 命令即可体验完整闭环。</Translate></p>
        </div>
        <div className={styles.serviceActions}>
          <Link className={styles.primaryAction} to="/docs/service/quick-deploy"><Translate id="home.deploy">本地部署 Service</Translate><span>→</span></Link>
          <Link className={styles.textAction} to="/docs/service/introduction"><Translate id="home.service.detail">了解 Service 能力</Translate><span>↗</span></Link>
        </div>
      </section>

      <section className={styles.finalCta}>
        <div><span>READY TO FORGE?</span><h2><Translate id="home.cta.title">从第一个 Agent 开始。</Translate></h2></div>
        <Link to="/docs/quickstart/getting-started"><Translate id="home.cta.action">阅读快速开始</Translate><b>↗</b></Link>
      </section>
    </main>
  </Layout>;
}
