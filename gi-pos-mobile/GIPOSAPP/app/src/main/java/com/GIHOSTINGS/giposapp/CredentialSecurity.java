package com.GIHOSTINGS.giposapp;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class CredentialSecurity {
  private static final int ITERATIONS = 120_000;
  private static final int KEY_BITS = 256;

  private CredentialSecurity() {}

  static String hashPin(String pin) throws Exception {
    byte[] salt = new byte[16];
    new SecureRandom().nextBytes(salt);
    byte[] hash = derive(pin, salt, ITERATIONS);
    return "pbkdf2-sha256$" + ITERATIONS + "$"
      + Base64.getEncoder().withoutPadding().encodeToString(salt) + "$"
      + Base64.getEncoder().withoutPadding().encodeToString(hash);
  }

  static boolean verifyPin(String pin, String stored) {
    try {
      String[] parts = stored == null ? new String[0] : stored.split("\\$");
      if (parts.length != 4 || !"pbkdf2-sha256".equals(parts[0])) return false;
      int iterations = Integer.parseInt(parts[1]);
      if (iterations < 100_000) return false;
      byte[] salt = Base64.getDecoder().decode(parts[2]);
      byte[] expected = Base64.getDecoder().decode(parts[3]);
      return MessageDigest.isEqual(expected, derive(pin, salt, iterations));
    } catch (Exception ignored) {
      return false;
    }
  }

  private static byte[] derive(String pin, byte[] salt, int iterations) throws Exception {
    PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS);
    try {
      return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    } finally {
      spec.clearPassword();
    }
  }
}
