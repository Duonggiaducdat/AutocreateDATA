package models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Mirrors the structure of {@code Services.json}:
 * <pre>
 * {
 *   "group": {
 *     "Trung tâm dữ liệu": {
 *       "services": ["Colocation","Dedicated","Rack"],
 *       "allowsRackOption": true,
 *       "rackOptions": ["Ít hơn 1 tủ","Nhiều hơn hoặc bằng 1 tủ"]
 *     },
 *     "Internet": { "services": ["..."], "allowsRackOption": false }
 *   }
 * }
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ServiceGroup {

    private Map<String, ServiceItem> group;

    public Map<String, ServiceItem> getGroup() { return group; }
    public void setGroup(Map<String, ServiceItem> group) { this.group = group; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ServiceItem {
        private List<String> services;
        private boolean allowsRackOption;
        private List<String> rackOptions;

        public List<String> getServices() { return services; }
        public void setServices(List<String> services) { this.services = services; }

        public boolean isAllowsRackOption() { return allowsRackOption; }
        public void setAllowsRackOption(boolean allowsRackOption) {
            this.allowsRackOption = allowsRackOption;
        }

        public List<String> getRackOptions() { return rackOptions; }
        public void setRackOptions(List<String> rackOptions) {
            this.rackOptions = rackOptions;
        }
    }
}
