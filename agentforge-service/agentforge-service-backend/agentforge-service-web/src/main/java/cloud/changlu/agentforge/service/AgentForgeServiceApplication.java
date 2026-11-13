package cloud.changlu.agentforge.service;

import cloud.changlu.agentforge.service.env.SystemPropertyUtil;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
public class AgentForgeServiceApplication {

    public static void main(String[] args) {
        // 解析外部 conf / logs 等目录，供 application.yml 与 logback.xml 引用
        SystemPropertyUtil.setSystemUserDir();
        new SpringApplicationBuilder(AgentForgeServiceApplication.class)
                .properties(
                        "spring.config.location=file:${user.dir.conf}/application.yml",
                        "logging.config=file:${user.dir.conf}/logback.xml")
                .run(args);
    }
}
