import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.request.AlipayTradePagePayRequest;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class PagePayGet {
    public static void main(String[] args) throws Exception {
        Map<String, String> env = new HashMap<>();
        for (String line : Files.readAllLines(Paths.get(args[0]))) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int i = line.indexOf('=');
            if (i < 0) continue;
            env.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
        }
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
        request.setNotifyUrl(env.get("ALIPAY_NOTIFY_URL"));
        request.setReturnUrl(env.get("ALIPAY_RETURN_URL"));

        String url = client.pageExecute(request, "GET").getBody();
        System.out.println("GET url = " + url.substring(0, Math.min(160, url.length())) + "...");
        for (int hop = 0; hop < 6 && url != null; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            int st = c.getResponseCode();
            String loc = c.getHeaderField("Location");
            System.out.println("hop " + hop + ": HTTP " + st + (loc != null ? "  -> " + loc : "  [final]"));
            if (loc == null) {
                InputStream is = st >= 400 ? c.getErrorStream() : c.getInputStream();
                String resp = is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
                String text = resp.replaceAll("(?s)<script.*?</script>", " ")
                        .replaceAll("(?s)<style.*?</style>", " ").replaceAll("<[^>]+>", " ")
                        .replaceAll("\\s+", " ").trim();
                System.out.println("final len=" + resp.length() + " text=" + text.substring(0, Math.min(500, text.length())));
                break;
            }
            url = loc;
        }
    }
}
