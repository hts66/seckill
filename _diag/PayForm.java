import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.request.AlipayTradePagePayRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class PayForm {
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
        model.setSubject("秒杀商城订单-DIAG");
        model.setProductCode("FAST_INSTANT_TRADE_PAY");
        AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
        request.setBizModel(model);
        request.setNotifyUrl(env.get("ALIPAY_NOTIFY_URL"));
        request.setReturnUrl(env.get("ALIPAY_RETURN_URL"));
        String form = client.pageExecute(request).getBody();
        String html = "<!doctype html><html><head><meta charset=\"utf-8\"></head><body>" + form + "</body></html>";
        Files.write(Paths.get(args[1]), html.getBytes(StandardCharsets.UTF_8));
        System.out.println("wrote " + args[1]);
    }
}
