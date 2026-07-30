package seatalk;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public class SeaTalkWsModels {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Header {
        @JsonProperty("app_id")
        public String appId;

        @JsonProperty("app_secret")
        public String appSecret;

        public String token;
        public String sid;

        @JsonProperty("callback_id")
        public String callbackId;

        public String rid;

        public Header() {
        }

        public static Header forRegister(String appId, String appSecret) {
            Header h = new Header();
            h.appId = appId;
            h.appSecret = appSecret;
            return h;
        }

        public static Header forAck(String token, String callbackId) {
            Header h = new Header();
            h.token = token;
            h.callbackId = callbackId;
            return h;
        }

        public static Header forPing(String token) {
            Header h = new Header();
            h.token = token;
            return h;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Envelope {
        public String cmd;
        public Header header;
        public Object data;
        public Integer code;
        public String message;

        public Envelope() {
        }

        public Envelope(String cmd, Header header) {
            this.cmd = cmd;
            this.header = header;
        }
    }

    public static class RegisterSettings {
        @JsonProperty("heartbeat_interval")
        public double heartbeatInterval;

        @JsonProperty("heartbeat_timeout")
        public double heartbeatTimeout;
    }
}