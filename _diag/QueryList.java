import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.domain.AlipayTradeQueryModel;
import com.alipay.api.response.AlipayTradeQueryResponse;
import java.nio.file.*;
import java.util.*;

public class QueryList {
    public static void main(String[] args) throws Exception {
        Map<String, String> env = new HashMap<>();
        for (String line : Files.readAllLines(Paths.get(args[0]))) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int i = line.indexOf('='); if (i < 0) continue;
            env.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
        }
        DefaultAlipayClient client = new DefaultAlipayClient(
                env.get("ALIPAY_GATEWAY_URL"), env.get("ALIPAY_APP_ID"), env.get("ALIPAY_PRIVATE_KEY"),
                "json", "UTF-8", env.get("ALIPAY_PUBLIC_KEY"), "RSA2");
        for (int k = 1; k < args.length; k++) {
            String outTradeNo = args[k];
            AlipayTradeQueryRequest req = new AlipayTradeQueryRequest();
            AlipayTradeQueryModel m = new AlipayTradeQueryModel();
            m.setOutTradeNo(outTradeNo);
            req.setBizModel(m);
            try {
                AlipayTradeQueryResponse r = client.execute(req);
                System.out.println(outTradeNo + " -> success=" + r.isSuccess()
                        + " tradeStatus=" + r.getTradeStatus() + " tradeNo=" + r.getTradeNo()
                        + " subCode=" + r.getSubCode());
            } catch (Exception e) {
                System.out.println(outTradeNo + " -> EXC " + e.getMessage());
            }
        }
    }
}
