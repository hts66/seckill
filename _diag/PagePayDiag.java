import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.request.AlipayTradePagePayRequest;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class PagePayDiag {
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
        model.setSubject("秒杀商城订单-DIAG");
        model.setProductCode("FAST_INSTANT_TRADE_PAY");

        AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
        request.setBizModel(model);
        request.setNotifyUrl(env.get("ALIPAY_NOTIFY_URL"));
        request.setReturnUrl(env.get("ALIPAY_RETURN_URL"));

        String formHtml = client.pageExecute(request).getBody();
        System.out.println("===== FORM HTML (first 1200 chars) =====");
        System.out.println(formHtml.substring(0, Math.min(1200, formHtml.length())));
        System.out.println("\n===== PARSED FORM =====");

        Matcher am = Pattern.compile("action=\"([^\"]+)\"").matcher(formHtml);
        String action = am.find() ? am.group(1).replace("&amp;", "&") : gateway;
        System.out.println("action = " + action);

        Map<String, String> fields = new LinkedHashMap<>();
        Matcher fm = Pattern.compile("name=\"([^\"]+)\"\\s+value=\"([^\"]*)\"", Pattern.DOTALL).matcher(formHtml);
        while (fm.find()) fields.put(fm.group(1), htmlUnescape(fm.group(2)));
        for (String k : fields.keySet())
            System.out.println("  " + k + " = " + (k.equals("biz_content") || k.equals("sign") ? fields.get(k).substring(0, Math.min(60, fields.get(k).length())) + "..." : fields.get(k)));

        // Submit exactly like the browser would (POST form), do NOT auto-follow redirects
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=')
                .append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(action).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream os = conn.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }

        int status = conn.getResponseCode();
        System.out.println("\n===== GATEWAY RESPONSE =====");
        System.out.println("HTTP status   = " + status);
        String location = conn.getHeaderField("Location");
        System.out.println("Location      = " + location);

        // Follow the redirect chain (up to 5 hops) and dump the final error page
        String url = location;
        for (int hop = 0; hop < 5 && url != null; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            int st = c.getResponseCode();
            String loc = c.getHeaderField("Location");
            System.out.println("hop " + hop + ": " + st + "  " + url + (loc != null ? "  ->  " + loc : ""));
            if (loc == null) {
                InputStream is = st >= 400 ? c.getErrorStream() : c.getInputStream();
                String resp = is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
                // strip tags to surface any human-readable error text
                String text = resp.replaceAll("(?s)<script.*?</script>", " ")
                        .replaceAll("(?s)<style.*?</style>", " ")
                        .replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
                System.out.println("final page text = " + text.substring(0, Math.min(600, text.length())));
                break;
            }
            url = loc;
        }
    }

    static String htmlUnescape(String s) {
        return s.replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
    }
}
