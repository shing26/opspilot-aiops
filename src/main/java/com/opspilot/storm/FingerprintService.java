package com.opspilot.storm;

import com.opspilot.retrieval.EsSearchService;
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
    // 错误码需保留（不同码=不同故障），先抽出占位再归一化其余。
    // 占位符必须是纯字母（数字会被 NUM 掩码破坏），下标用 26 进制字母编码
    // （a…z, aa, ab…），突破单字母 26 个上限；« 与 » 不与正文冲突，
    // 且整段字面匹配保证 «ECa» 不会命中 «ECan»（其后是 'n' 而非 '»'）。
    // 错误码词法单一事实源在 EsSearchService.ERROR_CODE（fast-path/指纹共用同一形状）。
    private static final Pattern ERROR_CODE = EsSearchService.ERROR_CODE;

    private static String ecToken(int index) {
        StringBuilder letters = new StringBuilder();
        int i = index;
        do {
            letters.insert(0, (char) ('a' + i % 26));
            i = i / 26 - 1;
        } while (i >= 0);
        return "«EC" + letters + "»";
    }

    public String normalizeError(String msg) {
        String s = msg == null ? "" : msg;
        // 1) 保护错误码：替换为稳定占位符（保留其身份，屏蔽周边噪声）
        java.util.List<String> codes = new ArrayList<>();
        Matcher ec = ERROR_CODE.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (ec.find()) {
            codes.add(ec.group());
            ec.appendReplacement(sb, ecToken(codes.size() - 1));
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
            s = s.replace(ecToken(i), codes.get(i));
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
