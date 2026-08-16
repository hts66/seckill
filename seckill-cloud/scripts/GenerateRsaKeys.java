import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

public class GenerateRsaKeys {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("需要指定密钥输出目录");
        Path directory = Path.of(args[0]);
        Files.createDirectories(directory);

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();

        writePem(directory.resolve("private.pem"), "PRIVATE KEY", pair.getPrivate().getEncoded());
        writePem(directory.resolve("public.pem"), "PUBLIC KEY", pair.getPublic().getEncoded());
    }

    private static void writePem(Path path, String type, byte[] bytes) throws Exception {
        String body = Base64.getMimeEncoder(64, System.lineSeparator().getBytes(StandardCharsets.US_ASCII))
                .encodeToString(bytes);
        String pem = "-----BEGIN " + type + "-----" + System.lineSeparator()
                + body + System.lineSeparator()
                + "-----END " + type + "-----" + System.lineSeparator();
        Files.writeString(path, pem, StandardCharsets.US_ASCII);
    }
}
