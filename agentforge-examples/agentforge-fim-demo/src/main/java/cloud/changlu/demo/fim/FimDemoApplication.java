package cloud.changlu.demo.fim;

import cloud.changlu.demo.fim.config.FimProperties;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * AgentForge FIM 对接演示。
 *
 * <p>启动后访问 {@code http://localhost:8080}，在左侧选一个案例， 光标会自动停在待补全位置，按 Tab 或停止输入即可看到补全效果。
 *
 * <p>包结构按职责分层：
 *
 * <ul>
 *   <li>{@code config} —— 配置属性与模型装配（只依赖框架的 LlmFactory）；
 *   <li>{@code web} —— HTTP 端点（协议适配，不含业务规则）；
 *   <li>{@code service} —— 请求组装与 Token 探测（业务规则集中处）；
 *   <li>{@code dto} —— 出入参对象。
 * </ul>
 *
 * @author changlu
 * @date 2026/11/26
 */
@SpringBootApplication
@EnableConfigurationProperties(FimProperties.class)
public class FimDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(FimDemoApplication.class, args);
    }
}
