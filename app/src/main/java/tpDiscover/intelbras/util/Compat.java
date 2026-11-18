package tpDiscover.intelbras.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Equivalentes Java 8 das APIs de Java 9+ usadas pelo projeto (String.isBlank, List.of,
 * Files.readString etc.). Mantém a mesma semântica: coleções imutáveis e hostis a null,
 * Set.of rejeitando duplicados. A ordem de iteração de setOf/mapOf é a de inserção.
 */
public final class Compat {

    private Compat() {
    }

    // ------------------------------------------------------------------ String

    public static boolean isBlank(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static String strip(String s) {
        return stripTrailing(stripLeading(s));
    }

    public static String stripLeading(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return s.substring(i);
    }

    public static String stripTrailing(String s) {
        int e = s.length();
        while (e > 0 && Character.isWhitespace(s.charAt(e - 1))) {
            e--;
        }
        return s.substring(0, e);
    }

    public static String repeat(String s, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count is negative: " + count);
        }
        StringBuilder sb = new StringBuilder(s.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    /** Equivalente a String.lines(): divide em \n, \r ou \r\n, sem linha vazia final. */
    public static Stream<String> lines(String s) {
        if (s.isEmpty()) {
            return Stream.empty();
        }
        List<String> out = new ArrayList<>();
        int start = 0;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r') {
                out.add(s.substring(start, i));
                if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            }
            i++;
        }
        if (start < s.length()) {
            out.add(s.substring(start));
        }
        return out.stream();
    }

    // ------------------------------------------------------------- Coleções

    @SafeVarargs
    public static <E> List<E> listOf(E... elementos) {
        for (E e : elementos) {
            Objects.requireNonNull(e);
        }
        return Collections.unmodifiableList(new ArrayList<>(Arrays.asList(elementos)));
    }

    @SafeVarargs
    public static <E> Set<E> setOf(E... elementos) {
        Set<E> set = new LinkedHashSet<>();
        for (E e : elementos) {
            if (!set.add(Objects.requireNonNull(e))) {
                throw new IllegalArgumentException("duplicate element: " + e);
            }
        }
        return Collections.unmodifiableSet(set);
    }

    public static <E> List<E> listCopyOf(Collection<? extends E> c) {
        List<E> copia = new ArrayList<>(c.size());
        for (E e : c) {
            copia.add(Objects.requireNonNull(e));
        }
        return Collections.unmodifiableList(copia);
    }

    public static <E> Set<E> setCopyOf(Collection<? extends E> c) {
        Set<E> copia = new LinkedHashSet<>();
        for (E e : c) {
            copia.add(Objects.requireNonNull(e));
        }
        return Collections.unmodifiableSet(copia);
    }

    public static <K, V> Map<K, V> mapCopyOf(Map<? extends K, ? extends V> m) {
        Map<K, V> copia = new LinkedHashMap<>();
        for (Map.Entry<? extends K, ? extends V> e : m.entrySet()) {
            copia.put(Objects.requireNonNull(e.getKey()), Objects.requireNonNull(e.getValue()));
        }
        return Collections.unmodifiableMap(copia);
    }

    public static <K, V> Map.Entry<K, V> entry(K k, V v) {
        return new AbstractMap.SimpleImmutableEntry<>(Objects.requireNonNull(k), Objects.requireNonNull(v));
    }

    public static <K, V> Map<K, V> mapOf() {
        return Collections.emptyMap();
    }

    public static <K, V> Map<K, V> mapOf(K k1, V v1) {
        return mapDe(k1, v1);
    }

    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2) {
        return mapDe(k1, v1, k2, v2);
    }

    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3) {
        return mapDe(k1, v1, k2, v2, k3, v3);
    }

    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4) {
        return mapDe(k1, v1, k2, v2, k3, v3, k4, v4);
    }

    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5) {
        return mapDe(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5);
    }

    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5,
                                         K k6, V v6) {
        return mapDe(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6);
    }

    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5,
                                         K k6, V v6, K k7, V v7) {
        return mapDe(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7);
    }

    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5,
                                         K k6, V v6, K k7, V v7, K k8, V v8) {
        return mapDe(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8);
    }

    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5,
                                         K k6, V v6, K k7, V v7, K k8, V v8, K k9, V v9) {
        return mapDe(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9);
    }

    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5,
                                         K k6, V v6, K k7, V v7, K k8, V v8, K k9, V v9, K k10, V v10) {
        return mapDe(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10);
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Map<K, V> mapDe(Object... kv) {
        Map<K, V> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            K k = (K) Objects.requireNonNull(kv[i]);
            V v = (V) Objects.requireNonNull(kv[i + 1]);
            if (map.put(k, v) != null) {
                throw new IllegalArgumentException("duplicate key: " + k);
            }
        }
        return Collections.unmodifiableMap(map);
    }

    // -------------------------------------------------------------- Arquivos

    public static String readString(Path caminho) throws IOException {
        return new String(Files.readAllBytes(caminho), StandardCharsets.UTF_8);
    }

    public static Path writeString(Path caminho, CharSequence conteudo, OpenOption... opcoes) throws IOException {
        return Files.write(caminho, conteudo.toString().getBytes(StandardCharsets.UTF_8), opcoes);
    }

    // ------------------------------------------------------------ Concorrência

    /**
     * Substituto de Executors.newVirtualThreadPerTaskExecutor() (Java 21+). Sem virtual threads,
     * cada tarefa ocupa uma thread de plataforma, então o pool é limitado a {@code maxThreads}
     * (o mesmo limite dos semáforos de quem submete) e as demais tarefas aguardam na fila.
     * Threads daemon, que expiram após 30s ociosas.
     */
    public static ExecutorService novoExecutor(String prefixoNome, int maxThreads) {
        AtomicInteger contador = new AtomicInteger();
        ThreadFactory fabrica = r -> {
            Thread t = new Thread(r, prefixoNome + "-" + contador.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        ThreadPoolExecutor pool = new ThreadPoolExecutor(maxThreads, maxThreads, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), fabrica);
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /** Equivalente ao ExecutorService.close() do Java 19+: encerra e aguarda todas as tarefas. */
    public static void encerrarEAguardar(ExecutorService executor) {
        executor.shutdown();
        boolean interrompido = false;
        while (true) {
            try {
                if (executor.awaitTermination(1, TimeUnit.DAYS)) {
                    break;
                }
            } catch (InterruptedException e) {
                if (!interrompido) {
                    executor.shutdownNow();
                    interrompido = true;
                }
            }
        }
        if (interrompido) {
            Thread.currentThread().interrupt();
        }
    }
}
