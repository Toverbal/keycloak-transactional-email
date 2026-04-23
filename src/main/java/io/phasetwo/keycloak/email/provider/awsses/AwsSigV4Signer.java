package io.phasetwo.keycloak.email.provider.awsses;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Computes AWS Signature Version 4 headers for a JSON POST request. */
class AwsSigV4Signer {

  private static final String ALGORITHM = "AWS4-HMAC-SHA256";
  private static final String SERVICE = "ses";
  private static final DateTimeFormatter DATE_TIME_FMT =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
  private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

  private AwsSigV4Signer() {}

  /**
   * Returns a two-element array: {@code [x-amz-date value, Authorization header value]}.
   *
   * @param accessKeyId AWS access key ID
   * @param secretAccessKey AWS secret access key
   * @param region AWS region (e.g. {@code us-east-1})
   * @param host hostname extracted from the endpoint URL (e.g. {@code email.us-east-1.amazonaws.com})
   * @param path URI path (e.g. {@code /v2/email/outbound-emails})
   * @param payload JSON request body
   * @param now request timestamp (UTC)
   */
  static String[] sign(
      String accessKeyId,
      String secretAccessKey,
      String region,
      String host,
      String path,
      String payload,
      ZonedDateTime now)
      throws Exception {

    String dateTime = now.format(DATE_TIME_FMT);
    String date = now.format(DATE_FMT);

    String bodyHash = sha256Hex(payload);
    String canonicalHeaders =
        "content-type:application/json\n" + "host:" + host + "\n" + "x-amz-date:" + dateTime + "\n";
    String signedHeaders = "content-type;host;x-amz-date";

    String canonicalRequest =
        "POST\n" + path + "\n\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + bodyHash;

    String credentialScope = date + "/" + region + "/" + SERVICE + "/aws4_request";
    String stringToSign =
        ALGORITHM + "\n" + dateTime + "\n" + credentialScope + "\n" + sha256Hex(canonicalRequest);

    byte[] signingKey =
        hmac(
            hmac(
                hmac(
                    hmac(("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8), date),
                    region),
                SERVICE),
            "aws4_request");

    String signature = hexEncode(hmac(signingKey, stringToSign));

    String authorization =
        ALGORITHM
            + " Credential="
            + accessKeyId
            + "/"
            + credentialScope
            + ", SignedHeaders="
            + signedHeaders
            + ", Signature="
            + signature;

    return new String[] {dateTime, authorization};
  }

  private static String sha256Hex(String data) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    return hexEncode(digest.digest(data.getBytes(StandardCharsets.UTF_8)));
  }

  private static byte[] hmac(byte[] key, String data) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
  }

  private static String hexEncode(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }
}
