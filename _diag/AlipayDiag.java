import com.alipay.api.AlipayApiException;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.domain.AlipayTradeQueryModel;
import com.alipay.api.response.AlipayTradeQueryResponse;

import java.nio.file.*;
import java.util.*;

public class AlipayDiag {
    public static void main(String[] args) throws Exception {
        Map<String, String> env = new HashMap<>();
        for (String line : Files.readAllLines(Paths.get(args[0]))) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int i = line.indexOf('=');
            if (i < 0) continue;
            env.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
        }
        String appId = env.get("ALIPAY_APP_ID");
        String priv = env.get("ALIPAY_PRIVATE_KEY");
        String pub = env.get("ALIPAY_PUBLIC_KEY");
        String gateway = env.get("ALIPAY_GATEWAY_URL");

        System.out.println("gateway = " + gateway);
        System.out.println("appId   = " + appId);
        System.out.println("privKey len = " + (priv == null ? "NULL" : priv.length()));
        System.out.println("pubKey  len = " + (pub == null ? "NULL" : pub.length()));
        System.out.println("------------------------------------------------------------");

        DefaultAlipayClient client = new DefaultAlipayClient(
                gateway, appId, priv, "json", "UTF-8", pub, "RSA2");

        AlipayTradeQueryRequest req = new AlipayTradeQueryRequest();
        AlipayTradeQueryModel model = new AlipayTradeQueryModel();
        model.setOutTradeNo("diag-" + UUID.randomUUID().toString().replace("-", ""));
        req.setBizModel(model);

        try {
            AlipayTradeQueryResponse resp = client.execute(req);
            System.out.println("isSuccess = " + resp.isSuccess());
            System.out.println("code      = " + resp.getCode());
            System.out.println("msg       = " + resp.getMsg());
            System.out.println("subCode   = " + resp.getSubCode());
            System.out.println("subMsg    = " + resp.getSubMsg());
            System.out.println("------------------------------------------------------------");
            System.out.println("body      = " + resp.getBody());
        } catch (AlipayApiException e) {
            System.out.println("!!! AlipayApiException (often = response sign-check with wrong ALIPAY_PUBLIC_KEY)");
            System.out.println("errCode = " + e.getErrCode());
            System.out.println("errMsg  = " + e.getErrMsg());
            e.printStackTrace();
        }
    }
}
