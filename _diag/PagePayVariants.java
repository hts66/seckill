import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.request.AlipayTradePagePayRequest;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class PagePayVariants {
    static Map<String, String> env = new HashMap<>();
    public static void main(String[] args) throws Exception {
        for (String line : Files.readAllLines(Paths.get(args[0]))) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int i = line.indexOf('=');
            if (i < 0) continue;
            env.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
        }
        run("A: no return/notify url", null, null);
        run("B: public https urls", "https://example.com/return", "https://example.com/notify");
        run("C: localhost urls (current)", env.get("ALIPAY_RETURN_URL"), env.get("ALIPAY_NOTIFY_URL"));
    }

    static void run(String label, String ret, String notify) throws Exception {
        DefaultAlipayClient client = new DefaultAlipayClient(
                env.get("ALIPAY_GATEWAY_URL"), env.get("ALIPAY_APP_ID"), env.get("ALIPAY_PRIVATE_KEY"),
                "json", "UTF-8", env.get("ALIPAY_PUBLIC_KEY"), "RSA2");
        AlipayTradePagePayModel model = new AlipayTradePagePayModel();
        model.setOutTradeNo("diag" + UUID.randomUUID().toString().replace("-", ""));
        model.setTotalAmount("0.01");
        model.setSubject("DIAG");
        model.setProductCode("FAST_INSTANT_TRADE_PAY");
        AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
        request.setBizModel(model);
        if (notify != null) request.setNotifyUrl(notify);
        if (ret != null) request.setReturnUrl(ret);

        String formHtml = client.pageExecute(request).getBody();
        Matcher am = Pattern.compile("action=\"([^\"]+)\"").matcher(formHtml);
        String action = am.find() ? am.group(1).replace("&amp;", "&") : env.get("ALIPAY_GATEWAY_URL");
        Map<String, String> fields = new LinkedHashMap<>();
        Matcher fm = Pattern.compile("name=\"([^\"]+)\"\\s+value=\"([^\"]*)\"", Pattern.DOTALL).matcher(formHtml);
        while (fm.find()) fields.put(fm.group(1), fm.group(2).replace("&quot;", "\"").replace("&amp;", "&"));
        fields.remove("");
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=').append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(action).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        try (OutputStream os = conn.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        System.out.println(label + "  =>  HTTP " + conn.getResponseCode() + "  Location=" + conn.getHeaderField("Location"));
    }
}
