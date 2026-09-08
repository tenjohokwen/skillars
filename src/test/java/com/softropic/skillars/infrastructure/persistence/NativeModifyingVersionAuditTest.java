package com.softropic.skillars.infrastructure.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.GenericTypeResolver;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-100 AC3 — a {@code @Modifying} bulk {@code UPDATE} bypasses Hibernate's
 * optimistic-lock check: JPA maps it straight to a SQL {@code UPDATE} and never touches the
 * {@code @Version} column. If a concurrent request thread is holding a managed instance of the same
 * row and {@code save()}s it, that stale write carries a version that still matches, so it silently
 * wins and the bulk update's effect is lost — with no {@code OptimisticLockingFailureException}
 * anywhere.
 *
 * <p>Two guards, deliberately overlapping:
 * <ol>
 *   <li>{@link #everyModifyingWriteAgainstAVersionTableIsAccountedFor()} scans every Spring Data
 *       repository whose <em>domain type</em> carries {@code @Version} and checks each of its
 *       {@code @Modifying} methods.</li>
 *   <li>{@link #noModifyingWriteAgainstAVersionedTableEscapesTheAudit()} is the completeness
 *       backstop for the AC's actual wording ("enumerate <em>every</em> native-SQL
 *       {@code @Modifying}"): it keys off the <em>write target</em> rather than the repository's
 *       domain type, so a native {@code UPDATE main.videos …} declared on a repository whose own
 *       entity is <em>not</em> {@code Video} (e.g. a cross-module cascade repo) is still caught.
 *       This removes the reliance on a one-time manual grep — a new un-audited write against any
 *       {@code @Version}-backed table fails the build regardless of where it is declared.</li>
 * </ol>
 *
 * <p>Each {@code @Modifying} write must be one of:
 * <ul>
 *   <li>a {@code DELETE} (nothing to bump), or</li>
 *   <li>an {@code INSERT} that is not an {@code ON CONFLICT … DO UPDATE} upsert, or</li>
 *   <li>an {@code UPDATE} whose statement explicitly contains {@code version = version + 1}, or</li>
 *   <li>listed in {@link #ALLOWED_WITHOUT_BUMP} with a written reason.</li>
 * </ul>
 *
 * <p>Deliberately broader than the AC's "native SQL" wording: a JPQL bulk {@code UPDATE} skips the
 * version bump exactly the same way (per the JPA spec, bulk update statements bypass optimistic
 * locking), so both are in scope here.
 *
 * <p><strong>Audit list at introduction (HEAD 2026-09-08):</strong>
 * <pre>
 *   repo.method                                     table                stmt    entity   verdict
 *   VideoRepository.resetLifecycleLockedAt          main.videos          UPDATE  Video    version=version+1 added (bulk; concurrent managed Video saves exist)
 *   RefreshTokenRepository.markAllUsedByUserId      main.refresh_tokens  UPDATE  RefreshToken  version=version+1 added by deferred-101 AC11 (defence-in-depth)
 *   RefreshTokenRepository.deleteExpiredTokens      main.refresh_tokens  DELETE  RefreshToken  exempt (DELETE)
 *   LoginAttemptRepository.deleteByAttemptedAtBefore  main.login_attempts  DELETE LoginAttempt exempt (DELETE, derived)
 *   PhoneOtpTokenRepository.deleteByUserIdAndUsedFalse  phone_otp_tokens  DELETE PhoneOtpToken exempt (DELETE)
 *   EmailVerificationTokenRepository.deleteByUserIdAndUsedFalse  email_verification_tokens DELETE EmailVerificationToken exempt (DELETE)
 * </pre>
 * No native ({@code nativeQuery = true}) {@code @Modifying} write against a {@code @Version} table
 * exists at HEAD; both flagged writes are JPQL. {@code Booking}, {@code SessionPackPurchase},
 * {@code SessionPackTier}, {@code CoachStripeAccount}, {@code Dispute}, {@code EnvelopeEntity},
 * {@code Drill} carry {@code @Version} but their repositories declare no {@code @Modifying} method,
 * and no {@code @Modifying} write anywhere targets their tables by name.
 */
@DisplayName("Every @Modifying write against a @Version table bumps the version or is allow-listed")
class NativeModifyingVersionAuditTest {

    /** methodKey ("SimpleName#method") -> why the missing version bump is safe. */
    private static final Map<String, String> ALLOWED_WITHOUT_BUMP = Map.of();
    // skillars-deferred-101 AC11: RefreshTokenRepository.markAllUsedByUserId moved from allow-list
    // to compliant (now includes version = version + 1) for defence-in-depth.

    private static final Pattern VERSION_BUMP = Pattern.compile(
        "version\\s*=\\s*(?:[a-zA-Z_][\\w]*\\.)?version\\s*\\+\\s*1", Pattern.CASE_INSENSITIVE);

    /**
     * Every identifier that follows {@code UPDATE} or {@code INSERT INTO} anywhere in the statement
     * — not anchored to the start, so a {@code WITH … AS (…) UPDATE <table> …} CTE (or a
     * multi-statement body) still surfaces its real write target (skillars-deferred-100 code review
     * 2026-09-08).
     */
    private static final Pattern WRITE_TARGET = Pattern.compile(
        "(?:\\bUPDATE|\\bINSERT\\s+INTO)\\s+([A-Z0-9_.\"]+)", Pattern.CASE_INSENSITIVE);

    private record ModSite(String key, String verdict) {
        @Override public String toString() { return key + " — " + verdict; }
    }

    @Test
    void everyModifyingWriteAgainstAVersionTableIsAccountedFor() throws Exception {
        List<Class<?>> versionedRepos = new ArrayList<>();
        var resolver = new PathMatchingResourcePatternResolver();
        var readers = new CachingMetadataReaderFactory(resolver);
        for (var resource : resolver.getResources("classpath*:com/softropic/skillars/**/*Repository.class")) {
            String className;
            try {
                className = readers.getMetadataReader(resource).getClassMetadata().getClassName();
            } catch (Exception e) {
                continue;
            }
            if (className.contains("$")) continue;
            Class<?> repo;
            try {
                repo = ClassUtils.forName(className, getClass().getClassLoader());
            } catch (Throwable e) {
                continue;
            }
            if (!repo.isInterface() || !Repository.class.isAssignableFrom(repo)) continue;
            Class<?>[] args = GenericTypeResolver.resolveTypeArguments(repo, Repository.class);
            Class<?> entity = (args != null && args.length >= 1) ? args[0] : null;
            if (entity != null && hasVersionField(entity)) {
                versionedRepos.add(repo);
            }
        }

        assertThat(versionedRepos)
            .as("the scan found no @Version repositories — it is asserting nothing")
            .isNotEmpty();

        List<ModSite> offenders = new ArrayList<>();
        for (Class<?> repo : versionedRepos) {
            for (Method m : repo.getMethods()) {  // getMethods(), not getDeclaredMethods(): also catches a
                                                  // @Modifying @Query inherited from a @NoRepositoryBean base
                                                  // (skillars-deferred-100 code review 2026-09-08)
                if (m.getAnnotation(Modifying.class) == null) continue;
                String key = repo.getSimpleName() + "#" + m.getName();
                Query q = m.getAnnotation(Query.class);

                if (q == null) {
                    // Derived modifying query — only the delete*/remove* forms are safe.
                    String n = m.getName().toLowerCase();
                    if (!n.startsWith("delete") && !n.startsWith("remove")) {
                        offenders.add(new ModSite(key, "derived @Modifying that is not a delete — add an explicit @Query"));
                    }
                    continue;
                }

                String stmt = normalise(q.value());
                if (stmt.startsWith("DELETE")) continue;
                if (stmt.startsWith("INSERT") && !stmt.contains("DO UPDATE")) continue;

                boolean isWrite = stmt.startsWith("UPDATE") || (stmt.startsWith("INSERT") && stmt.contains("DO UPDATE"));
                if (!isWrite) {
                    offenders.add(new ModSite(key, "unrecognised @Modifying statement: " + stmt));
                    continue;
                }
                if (VERSION_BUMP.matcher(stmt).find()) continue;
                if (ALLOWED_WITHOUT_BUMP.containsKey(key)) continue;

                offenders.add(new ModSite(key,
                    "UPDATE against a @Version table with no `version = version + 1` and not allow-listed"));
            }
        }

        assertThat(offenders)
            .as("""
                A @Modifying UPDATE against a @Version table must either SET version = version + 1 \
                (so a concurrent stale managed save is rejected instead of silently winning) or be \
                added to ALLOWED_WITHOUT_BUMP with a written reason why the missing bump is safe. \
                See skillars-deferred-100 AC3.""")
            .isEmpty();
    }

    /**
     * Completeness backstop: enumerate <em>every</em> {@code @Modifying} {@code @Query} on
     * <em>every</em> repository (not just repositories whose domain type is versioned) and flag any
     * whose write target — the table after {@code UPDATE} / {@code INSERT INTO} — backs a
     * {@code @Version} entity, unless it bumps the version or is allow-listed. Catches a native
     * write against a versioned table that a domain-type scan would miss.
     */
    @Test
    void noModifyingWriteAgainstAVersionedTableEscapesTheAudit() throws Exception {
        var resolver = new PathMatchingResourcePatternResolver();
        var readers = new CachingMetadataReaderFactory(resolver);

        // 1. Every @Version entity -> the identifier tokens a write statement could name it by:
        //    the JPQL entity simple name, the bare table name, and the schema-qualified table name.
        Map<String, String> versionedTargets = new LinkedHashMap<>(); // token (UPPER) -> entity simple name
        for (var resource : resolver.getResources("classpath*:com/softropic/skillars/**/*.class")) {
            String className;
            try {
                var meta = readers.getMetadataReader(resource);
                if (!meta.getAnnotationMetadata().hasAnnotation(Entity.class.getName())) continue;
                className = meta.getClassMetadata().getClassName();
            } catch (Exception e) {
                continue;
            }
            if (className.contains("$")) continue;
            Class<?> entity;
            try {
                entity = ClassUtils.forName(className, getClass().getClassLoader());
            } catch (Throwable e) {
                continue;
            }
            if (!hasVersionField(entity)) continue;

            String simple = entity.getSimpleName();
            String table = simple;
            String schema = "";
            Table t = entity.getAnnotation(Table.class);
            if (t != null && !t.name().isBlank()) table = t.name();
            else table = camelToSnake(simple);
            if (t != null && !t.schema().isBlank()) schema = t.schema();

            for (String token : new String[] {simple, table, schema.isBlank() ? null : schema + "." + table}) {
                if (token != null) versionedTargets.putIfAbsent(token.toUpperCase(), simple);
            }
        }

        assertThat(versionedTargets)
            .as("no @Version entities discovered — the completeness scan is asserting nothing")
            .isNotEmpty();

        // 2. Every @Modifying @Query on every repository; flag writes that target a versioned table.
        List<ModSite> offenders = new ArrayList<>();
        Set<String> auditedVersionedTargets = new LinkedHashSet<>();
        for (var resource : resolver.getResources("classpath*:com/softropic/skillars/**/*Repository.class")) {
            String className;
            try {
                className = readers.getMetadataReader(resource).getClassMetadata().getClassName();
            } catch (Exception e) {
                continue;
            }
            if (className.contains("$")) continue;
            Class<?> repo;
            try {
                repo = ClassUtils.forName(className, getClass().getClassLoader());
            } catch (Throwable e) {
                continue;
            }
            if (!repo.isInterface() || !Repository.class.isAssignableFrom(repo)) continue;

            for (Method m : repo.getMethods()) {  // getMethods(), not getDeclaredMethods(): also catches a
                                                  // @Modifying @Query inherited from a @NoRepositoryBean base
                                                  // (skillars-deferred-100 code review 2026-09-08)
                if (m.getAnnotation(Modifying.class) == null) continue;
                Query q = m.getAnnotation(Query.class);
                if (q == null) continue; // derived deletes handled by the first test
                String stmt = normalise(q.value());
                if (stmt.startsWith("DELETE")) continue;
                if (stmt.startsWith("INSERT") && !stmt.contains("DO UPDATE")) continue;

                String key = repo.getSimpleName() + "#" + m.getName();

                // Check *every* UPDATE / INSERT INTO target in the statement (CTE bodies included).
                Matcher tm = WRITE_TARGET.matcher(stmt);
                while (tm.find()) {
                    String target = tm.group(1).replace("\"", "").toUpperCase();
                    String entitySimple = versionedTargets.get(target);
                    if (entitySimple == null) continue; // not a versioned table

                    auditedVersionedTargets.add(target);
                    if (VERSION_BUMP.matcher(stmt).find()) continue;
                    if (ALLOWED_WITHOUT_BUMP.containsKey(key)) continue;

                    offenders.add(new ModSite(key,
                        "writes " + entitySimple + " (@Version) via `" + target
                            + "` with no `version = version + 1` and not allow-listed"));
                }
            }
        }

        assertThat(offenders)
            .as("""
                Completeness check (skillars-deferred-100 AC3): a @Modifying write whose target \
                table backs a @Version entity must SET version = version + 1 or be in \
                ALLOWED_WITHOUT_BUMP with a reason — no matter which repository declares it.""")
            .isEmpty();

        // skillars-deferred-100 code review (2026-09-08): guard the guard. If the write-target
        // matching ever silently drifts to zero classified rows (regex change, naming-strategy
        // mismatch), `offenders` is trivially empty and this test would pass while auditing nothing.
        // At HEAD exactly one versioned write target is audited: main.videos, via
        // VideoRepository.resetLifecycleLockedAt.
        assertThat(auditedVersionedTargets)
            .as("the completeness scan classified no @Modifying write against any @Version table — "
                + "it is asserting nothing; check WRITE_TARGET / versionedTargets resolution")
            .isNotEmpty();
    }

    private static boolean hasVersionField(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.isAnnotationPresent(Version.class)) return true;
            }
        }
        return false;
    }

    /**
     * Uppercase, collapse whitespace, and strip both {@code --} line comments and {@code / * … * /}
     * block comments so {@code startsWith} / target-token checks hold even when a query opens with a
     * comment (skillars-deferred-100 code review 2026-09-08).
     */
    private static String normalise(String jpqlOrSql) {
        return jpqlOrSql
            .replaceAll("(?s)/\\*.*?\\*/", " ")
            .replaceAll("--[^\\n]*", " ")
            .replaceAll("\\s+", " ")
            .trim()
            .toUpperCase();
    }

    /** {@code EmailVerificationToken} -> {@code email_verification_token} (JPA's default table name). */
    private static String camelToSnake(String s) {
        return s.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}
