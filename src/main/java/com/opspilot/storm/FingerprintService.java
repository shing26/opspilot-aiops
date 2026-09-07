package com.opspilot.storm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
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
    private static final Pattern HEX = Pattern.compile("\\b0x[0-9a-fA-F]+\\b|\\b[0-9a-fA-F]{16,}\\b");
    private static final Pattern NUM = Pattern.compile("\\d+");
    private static final Pattern ID_KV = Pattern.compile("(?i)(msg_?id|trace_?id|request_?id|order_?id|span_?id|txn_?id|[0-9a-f]{6,})\\s*[=:]\\s*\\S+");
    private static final Pattern QUOTED = Pattern.compile("\"[^\"]*\"|'[^']*'");
    private static final Pattern WS = Pattern.compile("\\s+");
    // 错误码需保留（不同码=不同故障），先抽出占位再归一化其余
    private static final Pattern ERROR_CODE = Pattern.compile("\\b\\d{5}_[A-Z][A-Z0-9_]*\\b");

    public String normalizeError(String msg) {
        String s = msg == null ? "" : msg;
        // 1) 保护错误码：替换为稳定占位符（保留其身份，屏蔽周边噪声）
        java.util.List<String> codes = new ArrayList<>();
        Matcher ec = ERROR_CODE.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (ec.find()) {
            codes.add(ec.group());
            ec.appendReplacement(sb, "«EC" + (char) ('a' + codes.size() - 1) + "»");
        }
        ec.appendTail(sb);
        s = sb.toString();
        // 2) 掩码高基数噪声
        s = TS.matcher(s).replaceAll("<TS>");
        s = UUID.matcher(s).replaceAll("<UUID>");
        s = ID_KV.matcher(s).replaceAll("<ID>");
        s = QUOTED.matcher(s).replaceAll("<STR>");
        s = HEX.matcher(s).replaceAll("<HEX>");
        s = NUM.matcher(s).replaceAll("<N>");
        s = WS.matcher(s.trim()).replaceAll(" ");
        // 3) 还原错误码
        for (int i = 0; i < codes.size(); i++) {
            s = s.replace("«EC" + (char) ('a' + i) + "»", codes.get(i));
        }
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
