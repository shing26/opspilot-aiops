package com.opspilot.storm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 告警指纹：SHA256(service + env + normalized_error_msg)。
 * 归一化剥离时间戳/UUID/十六进制地址/数字，使同质告警收敛到同一指纹。
 */
@Service
public class FingerprintService {

    private static final Pattern TS = Pattern.compile("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?");
    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern HEX = Pattern.compile("0x[0-9a-fA-F]+");
    private static final Pattern NUM = Pattern.compile("\\b\\d+\\b");
    private static final Pattern WS = Pattern.compile("\\s+");

    public String normalizeError(String msg) {
        String s = msg == null ? "" : msg;
        s = TS.matcher(s).replaceAll("<TS>");
        s = UUID.matcher(s).replaceAll("<UUID>");
        s = HEX.matcher(s).replaceAll("<HEX>");
        s = NUM.matcher(s).replaceAll("<N>");
        s = WS.matcher(s.trim()).replaceAll(" ");
        return s;
    }

    public String fingerprint(String service, String env, String errorMsg) {
        String raw = (service == null ? "" : service) + "|" + (env == null ? "" : env)
                + "|" + normalizeError(errorMsg);
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
