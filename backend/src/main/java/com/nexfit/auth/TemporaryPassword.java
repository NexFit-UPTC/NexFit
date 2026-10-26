package com.nexfit.auth;

/** Contraseña temporal emitida por AuthService: texto plano (una sola vez) y su hash. */
public record TemporaryPassword(String plaintext, String hash) { }
