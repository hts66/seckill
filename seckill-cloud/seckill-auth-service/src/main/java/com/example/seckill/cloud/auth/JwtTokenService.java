package com.example.seckill.cloud.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtTokenService {
    private final UserRepository users; private final StringRedisTemplate redis; private final PrivateKey privateKey; private final PublicKey publicKey;
    private final long accessTtl,refreshTtl;
    public JwtTokenService(UserRepository users,StringRedisTemplate redis,@Value("${security.jwt.private-key:}")String configuredPrivatePem,
                           @Value("${security.jwt.public-key:}")String configuredPublicPem,
                           @Value("${security.jwt.private-key-file:keys/private.pem}")String privateKeyFile,
                           @Value("${security.jwt.public-key-file:keys/public.pem}")String publicKeyFile,
                           @Value("${security.jwt.access-ttl:1800000}")long accessTtl,
                           @Value("${security.jwt.refresh-ttl:604800000}")long refreshTtl)throws Exception{
        this.users=users;this.redis=redis;this.accessTtl=accessTtl;this.refreshTtl=refreshTtl;
        String privatePem=loadPem(configuredPrivatePem,privateKeyFile,"JWT private key");
        String publicPem=loadPem(configuredPublicPem,publicKeyFile,"JWT public key");
        this.privateKey=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decode(privatePem,"PRIVATE")));
        this.publicKey=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decode(publicPem,"PUBLIC")));
    }
    public AuthDtos.Tokens issue(UserAccount user){String jti=UUID.randomUUID().toString();Date now=new Date();String access=Jwts.builder().subject(user.id().toString()).claim("email",user.email()).claim("role",user.role()).claim("tokenVersion",user.tokenVersion()).claim("type","access").issuedAt(now).expiration(new Date(now.getTime()+accessTtl)).signWith(privateKey).compact();
        String refresh=Jwts.builder().subject(user.id().toString()).id(jti).claim("tokenVersion",user.tokenVersion()).claim("type","refresh").issuedAt(now).expiration(new Date(now.getTime()+refreshTtl)).signWith(privateKey).compact();
        redis.opsForValue().set("auth:refresh:"+jti,user.id()+":"+user.tokenVersion(),Duration.ofMillis(refreshTtl));return new AuthDtos.Tokens(access,refresh,view(user));}
    public AuthDtos.Tokens refresh(String token){Claims c=parse(token);if(!"refresh".equals(c.get("type",String.class))||c.getId()==null)throw new IllegalArgumentException("刷新令牌无效");String key="auth:refresh:"+c.getId();String session=redis.opsForValue().get(key);UserAccount user=users.findById(Long.valueOf(c.getSubject())).orElseThrow(()->new IllegalArgumentException("登录状态已失效"));Integer version=c.get("tokenVersion",Integer.class);
        if(session==null||version==null||version!=user.tokenVersion()||user.status()!=1)throw new IllegalArgumentException("登录状态已失效");redis.delete(key);return issue(user);}
    public void revoke(String token){try{Claims c=parse(token);if(c.getId()!=null)redis.delete("auth:refresh:"+c.getId());}catch(Exception ignored){}}
    private Claims parse(String token){return Jwts.parser().verifyWith(publicKey).build().parseSignedClaims(token).getPayload();}
    private AuthDtos.UserView view(UserAccount u){return new AuthDtos.UserView(u.id(),u.email(),u.username(),u.avatar(),u.role());}
    private static byte[] decode(String pem,String type){return Base64.getDecoder().decode(pem.replace("-----BEGIN "+type+" KEY-----","").replace("-----END "+type+" KEY-----","").replaceAll("\\s",""));}
    private static String loadPem(String configuredValue,String file,String description)throws IOException{
        if(configuredValue!=null&&!configuredValue.isBlank())return configuredValue.replace("\\n","\n");
        for(Path candidate:new Path[]{Path.of(file),Path.of("..",file),Path.of("seckill-cloud",file)}){
            Path absolute=candidate.toAbsolutePath().normalize();
            if(Files.isRegularFile(absolute))return Files.readString(absolute);
        }
        throw new IllegalStateException(description+" not found. Set the JWT key environment variable or place the key at "+Path.of(file).toAbsolutePath().normalize());
    }
}
