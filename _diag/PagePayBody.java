import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.request.AlipayTradePagePayRequest;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class PagePayBody {
    public static void main(String[] args) throws Exception {
        Map<String, String> env = new HashMap<>();
        for (String line : Files.readAllLines(Paths.get(args[0]))) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int i = line.indexOf('=');
            if (i < 0) continue;
            env.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
        }
        String gateway = env.get("ALIPAY_GATEWAY_URL");
        DefaultAlipayClient client = new DefaultAlipayClient(
                gateway, env.get("ALIPAY_APP_ID"), env.get("ALIPAY_PRIVATE_KEY"),
                "json", "UTF-8", env.get("ALIPAY_PUBLIC_KEY"), "RSA2");
        AlipayTradePagePayModel model = new AlipayTradePagePayModel();
        model.setOutTradeNo("diag" + UUID.randomUUID().toString().replace("-", ""));
        model.setTotalAmount("0.01");
        model.setSubject("DIAG");
        model.setProductCode("FAST_INSTANT_TRADE_PAY");
        AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
        request.setBizModel(model);
        request.setNotifyUrl(env.get("ALIPAY_NOTIFY_URL"));
        request.setReturnUrl(env.get("ALIPAY_RETURN_URL"));

        String formHtml = client.pageExecute(request).getBody();
        Matcher am = Pattern.compile("action=\"([^\"]+)\"").matcher(formHtml);
        String action = am.find() ? am.group(1).replace("&amp;", "&") : gateway;

        // Collect ALL params: from the action URL query string + the biz_content hidden field
        Map<String, String> all = new LinkedHashMap<>();
        String query = action.substring(action.indexOf('?') + 1);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            all.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
        }
        Matcher fm = Pattern.compile("name=\"([^\"]+)\"\\s+value=\"([^\"]*)\"", Pattern.DOTALL).matcher(formHtml);
        while (fm.find()) {
            String v = fm.group(2).replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
            all.put(fm.group(1), v);
        }
        all.remove("");

        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> e : all.entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=').append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        String bare = gateway; // no query string
        System.out.println("POST all " + all.size() + " params in body -> " + bare);
        HttpURLConnection conn = (HttpURLConnection) new URL(bare).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        try (OutputStream os = conn.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        System.out.println("HTTP " + conn.getResponseCode() + "  Location=" + conn.getHeaderField("Location"));
    }
}
