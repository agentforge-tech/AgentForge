package cloud.changlu.agentforge.service.modules.plugins.http.domain.req;

import java.util.Map;

public class HttpPluginTestRunReq {
    private Map<String, Object> arguments;

    public Map<String, Object> getArguments() {
        return arguments;
    }

    public void setArguments(Map<String, Object> v) {
        arguments = v;
    }
}
