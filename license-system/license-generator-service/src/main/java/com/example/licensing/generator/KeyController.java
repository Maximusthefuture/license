package com.example.licensing.generator;

import com.example.licensing.issuer.keystore.KeyEventLog;
import com.example.licensing.issuer.keystore.KeyRing;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Base64;
import java.util.List;

/**
 * Signing key management, ADMIN role only (see SecurityConfiguration).
 *
 * <pre>
 * GET  /api/v1/keys                    keys on the volume, which one is active, recent key events
 * POST /api/v1/keys                    {"keyId": "2026-01"} -> new key on the volume, NOT active
 * POST /api/v1/keys/{kid}/activate     {"confirm": true} -> new licenses are signed with this key
 * GET  /api/v1/keys/{kid}/backup       the encrypted .p12, for an offline backup
 * POST /api/v1/keys/reserve            {"keyId", "password"} -> reserve .p12 to download; not stored here
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/keys")
class KeyController {

    private final KeyRing keyRing;

    KeyController(KeyRing keyRing) {
        this.keyRing = keyRing;
    }

    @GetMapping
    KeysResponse keys() {
        List<KeyView> keys = keyRing.list().stream().map(KeyView::of).toList();
        String activeKeyId = keyRing.active().map(k -> k.keyId()).orElse(null);
        return new KeysResponse(activeKeyId, keys,
                keyRing.events(50).stream().map(EventView::of).toList());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<KeyView> create(@RequestBody CreateKeyBody body, Principal admin) {
        KeyRing.KeyInfo created = keyRing.generate(body.keyId(), admin.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(KeyView.of(created));
    }

    @PostMapping(path = "/{keyId}/activate", consumes = MediaType.APPLICATION_JSON_VALUE)
    KeyView activate(@PathVariable("keyId") String keyId, @RequestBody ActivateBody body, Principal admin) {
        if (!body.confirm()) {
            throw new IllegalArgumentException("activation must be confirmed: the public key of '" + keyId
                    + "' has to be in TrustedKeys of the builds customers run, or they will reject new licenses");
        }
        return KeyView.of(keyRing.activate(keyId, admin.getName()));
    }

    @GetMapping("/{keyId}/backup")
    ResponseEntity<byte[]> backup(@PathVariable("keyId") String keyId, Principal admin) {
        byte[] pkcs12 = keyRing.backup(keyId, admin.getName());
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + keyId + ".p12\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(pkcs12);
    }

    @PostMapping(path = "/reserve", consumes = MediaType.APPLICATION_JSON_VALUE)
    ReserveResponse reserve(@RequestBody ReserveBody body, Principal admin) {
        if (body.password() == null) {
            throw new IllegalArgumentException("a password for the reserve keystore is required");
        }
        KeyRing.ReserveKey reserve = keyRing.generateReserve(body.keyId(), body.password().toCharArray(),
                admin.getName());
        return new ReserveResponse(KeyView.of(reserve.info()), reserve.info().keyId() + ".p12",
                Base64.getEncoder().encodeToString(reserve.pkcs12()));
    }

    // ---- bodies ----

    record CreateKeyBody(String keyId) {
    }

    record ActivateBody(boolean confirm) {
    }

    record ReserveBody(String keyId, String password) {
    }

    record KeysResponse(String activeKeyId, List<KeyView> keys, List<EventView> events) {
    }

    record KeyView(String keyId, String createdAt, boolean active, String publicKeyBase64,
                   String trustedKeysLine, String problem) {
        static KeyView of(KeyRing.KeyInfo k) {
            return new KeyView(k.keyId(), k.createdAt() == null ? null : k.createdAt().toString(), k.active(),
                    k.publicKeyBase64(), k.trustedKeysLine(), k.problem());
        }
    }

    record EventView(String at, String action, String keyId, String actor) {
        static EventView of(KeyEventLog.KeyEvent e) {
            return new EventView(e.at().toString(), e.action(), e.keyId(), e.actor());
        }
    }

    record ReserveResponse(KeyView key, String fileName, String keystoreBase64) {
    }
}
