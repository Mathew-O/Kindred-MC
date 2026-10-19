package dev.kindred.core;

import java.util.Locale;
import java.util.Optional;

/** NameMC texture hashes for the canonical trio player skins. */
public final class CanonicalRosterSkins {

    public record SkinBinding(String textureHash, boolean slim, String clientAssetPath, String textureValue, String textureSignature) {
        public SkinBinding(String textureHash, boolean slim, String clientAssetPath) {
            this(textureHash, slim, clientAssetPath, null, null);
        }

        public String mojangTextureUrl() {
            return "http://textures.minecraft.net/texture/" + textureHash;
        }
    }

    public static final SkinBinding KINDRED = new SkinBinding(
            "1486caf0fd31886ae1dea6ebc4644880342ce7db347f83b644b629c863ad696",
            true,
            "textures/entity/roster/kindred.png",
            "ewogICJ0aW1lc3RhbXAiIDogMTc5MjQ0MzA4ODgwNywKICAicHJvZmlsZUlkIiA6ICI1MGUwODY2ZGNmMTg0ZmMzYWRjMGQ3MDYyODkxZmI0YiIsCiAgInByb2ZpbGVOYW1lIiA6ICJLaW5kcmVkIiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzE0ODZjYWYwZmQzMTg4NmFlMWRlYTZlYmM0NjQ0ODgwMzQyY2U3ZGIzNDdmODNiNjQ0YjYyOWM4NjNhZDY5NiIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9",
            "A8pcLNt9Edd3KhiwfDFYdTixMTUEBHGQoz8H/XAP+/ljH9xA9XQ6W2ppOIBXPEAsUv71EcCgzAV1j5Yvk0LX91C2SMKZMrt5nog0BLE+4JblINNOQz4F7CqnLeX4mvWXzK2LnFINMGm+/1kYKzisliU9+/ozqZQ3oWT1qc+RewZP9pmRZ4AZJRhwW2Qxl6+8Yv4kmSf0XqrYlJuFaZNoKuJFRMT6Wa0SEPU32aPbdCW5AuyMoTW5XwWyNZOnpt08qrmziaX367ZXBViIGqLH5NPkCjxRdahl2FYdx700PwlP7U1Kj+eghj0pWDglQZA0qN26WsxnrOnALZ8I76BbHFF2zBzxXWe37/J6CejDXm676xdbnX1X9iDcF+SSX93AjZxOwTfFNCeiYm5IDRujvkc+de8QxDRWXCZgLWGyCq/gx1R/FOpMkvnp294eM7Fcnlwj5yfjSYz1hxzWDV8Uk+svGQQDtQI54Flgl2ItnfLcXXOKb/7rUnjTIbtrS1Qwzve7qAgsg9+o0pVUPVLSa3SUfDr9nfmRdx9Bv6kD0Y6AsmgwBZflvMip39cZaHV2BisfNMd0FlJwYDSsqr4DHHI0WeC2k6a5uc5dKUmSDw2zNXVLUOOkJSQ9xOokuyWyQtAY2d0SpB10Das7ivV5F2yquHmFg8rBUzU0I8KC5wg="
    );
    public static final SkinBinding SOL = new SkinBinding(
            "df426aeb3cd1998e376ffd4403368ea3f4631a4ee291680d6848f99b9f372547",
            true,
            "textures/entity/roster/sol.png",
            "ewogICJ0aW1lc3RhbXAiIDogMTc5MjQ0MzA5NjI4MSwKICAicHJvZmlsZUlkIiA6ICJjMjU1M2VmNTI5Nzk0ZGEwYmM5OGZlMjcwOGZkYWFlYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJzb2wiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZGY0MjZhZWIzY2QxOTk4ZTM3NmZmZDQ0MDMzNjhlYTNmNDYzMWE0ZWUyOTE2ODBkNjg0OGY5OWI5ZjM3MjU0NyIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9",
            "vNQPJf8Ys4vXZl5tMBaJwAkce3H6VNR8QK1EfGfFW0hZaEALnFmEQF6KKYuuOlaO0T3nkO9PvqQVB7r/7C5T1+TI5ImgQZGBBT/2ZahrajY542Z/CPezoBAV/J95uArRwrAg3Q9kDMt6/96awVJjmI78Fobiv6YFTJUH80Q+ajHVxdx8+mRpjTNqv46MKiS3YkbF+6KKYjPtBySsT10BNY9dHyoIidmGqEi2ia7K1uNcE9bqgmOG7WVMlvGIVMTP3UA5nNYv5ZBxouyMg9DJuM+OstTE9VHUz9hmISwyuy6MBo/4DH67ynfIM7+NfPdDVgOG1hWmk9IZMOlkELEbuXWG5t1XGBjQPopwLIKuSH3PdqH6e6sdjG3itLz5dAc8gR9GfPwf3YfzCouw6QnUPAKfYfcxyh6m5a105Q/lhDDblYbhQpTG3QwNxtajBqJqEbqs4Mqq6g7hpB+oYvSFB7pGkAkiYRx8EZ+CUiMLTirP2gIYuEdlfcAAGf3C65FwPGTXraBnwx4O1SHf1sdg+NNqqd78Z/gMclrEQC7McRrniq2m0snX7QMNgZtywW0Sl9bhUcVp2LHjUrfBjmKVlSfBFSA4efUWmecHSdzvXvKn+W2MXW8ztyqnYmQHFWzoPLUjJukV5cVwqn45ixTDuSc2acStSvlYgElycTPN/5Y="
    );
    public static final SkinBinding VESP = new SkinBinding(
            "19f928e039383a9c911e26917a6d221f71b387eb59d81753e75c0332bd749e8e",
            false,
            "textures/entity/roster/vesp.png",
            "ewogICJ0aW1lc3RhbXAiIDogMTc5MjQ0MzE0NTc5NCwKICAicHJvZmlsZUlkIiA6ICI2ZmExNzUwMDk5OGY0ZDFlYTc4NTg1N2RiOTRmYTUzMiIsCiAgInByb2ZpbGVOYW1lIiA6ICJWZXNwIiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzE5ZjkyOGUwMzkzODNhOWM5MTFlMjY5MTdhNmQyMjFmNzFiMzg3ZWI1OWQ4MTc1M2U3NWMwMzMyYmQ3NDllOGUiCiAgICB9CiAgfQp9",
            "yI+qqpZN2NxvNRvtX26Fp23jdmtttWU9jyMGiqBMK97+pP5NWuwq3ErFx0AzpHWFxvhlvhvridJ7nJw8U1gWoCxH1nIdQ6nNN7X0XO35NVyX1UiKPvghTCQOQPPjet0P9iHluOHVstQsMPnGHdF/U80aGMcXNKI/mNRoB/qbNArzFP0Q3f0t9B0iJyvSNY4T3KlsJPrOL5HYa7TxDk4vje9EgZDhMDhAFcFBAAwgZ95dG9sqD2aAIZeFIkXmTnUo7vB3DF9xay2y5/oFTN2sEexwfun0tGbQfIXoFOas17BALgC8aCo8uZmnGs0w4dKlXny3gfXVU8CMnDYqjax4mEi/WK7tr8BAa+qf4f3I7vKhsaWpqoKOPIvkIYkTum3G/d4d5cmoa8/uZfsQ6N5dNRSCXAZTtRZsD6nBDdy0ktdhRqG/3rQQOFSkXD4ozvrUa45M9XZWnLv8yK6n6RUTlI701pL9bKddWDQ33MXJQtqX63b/kFP5ElFLFy0WkXUmdHSqrJ0NsJLs3Dp+1EqrmERKEDldbvZ0/06J52I27NX+wlt/eYRRWh/59DEjxhP2lPNz6EDadhP+r/zboMJan96Icps898H+c9ehBaGTvrfEP9CXZCZGVaWG1q2MZBu1O9MS6XJdLvn4j2GkSPjZ195ymoOND2USU6L9kQOGjpU="
    );

    public static Optional<SkinBinding> forDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return Optional.empty();
        }
        String key = displayName.trim().toLowerCase(Locale.ROOT);
        if (CanonicalRoster.KINDRED_NAME.toLowerCase(Locale.ROOT).equals(key)) {
            return Optional.of(KINDRED);
        }
        if (CanonicalRoster.SOL_NAME.toLowerCase(Locale.ROOT).equals(key)) {
            return Optional.of(SOL);
        }
        if (CanonicalRoster.VESP_NAME.toLowerCase(Locale.ROOT).equals(key)) {
            return Optional.of(VESP);
        }
        return Optional.empty();
    }

    private CanonicalRosterSkins() { }
}