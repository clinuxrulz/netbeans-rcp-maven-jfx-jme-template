package com.psf.cursed_signals;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public final class CS {

    public interface Accessor<A> extends Supplier<A> {}
    public interface Setter<A> extends Consumer<A> {}
    public record Signal<A>(Accessor<A> get, Setter<A> set) {}

    private static class Node {
        public enum State { CLEAN, STALE, DIRTY }
        
        private State state = State.DIRTY;
        private boolean eager;
        private Set<Node> children;
        private Deque<Runnable> cleanups;
        private Set<Node> sources;
        private Set<Node> sinks;
        private Supplier<Boolean> update;

        public Node(boolean eager) {
            this.eager = eager;
        }
    }

    private static Node owner = null;
    private static Node observer = null;
    private static final Set<Node> cursorSet = new HashSet<>();
    private static int transactionDepth = 0;

    private CS() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    private static <A> A transaction(Supplier<A> k) {
        ++transactionDepth;
        A result;
        try {
            result = k.get();
        } finally {
            --transactionDepth;
        }
        if (transactionDepth == 0) {
            flush();
        }
        return result;
    }

    private static void flush() {
        while (!cursorSet.isEmpty()) {
            Set<Node> cursors = new HashSet<>(cursorSet);
            cursorSet.clear();
            for (Node cursor : cursors) {
                resolveNode(cursor);
            }
        }
    }

    private static <A> A useOwner(Node innerOwner, Supplier<A> k) {
        Node oldOwner = owner;
        owner = innerOwner;
        try {
            return k.get();
        } finally {
            owner = oldOwner;
        }
    }

    private static <A> A useObserver(Node innerObserver, Supplier<A> k) {
        Node oldObserver = observer;
        observer = innerObserver;
        try {
            return k.get();
        } finally {
            observer = oldObserver;
        }
    }

    private static <A> A useOwnerAndObserver(Node innerNode, Supplier<A> k) {
        Node oldOwner = owner;
        Node oldObserver = observer;
        owner = innerNode;
        observer = innerNode;
        try {
            return k.get();
        } finally {
            owner = oldOwner;
            observer = oldObserver;
        }
    }

    private static void dirtyTheSinks(Node node) {
        if (node.sinks == null) {
            return;
        }
        for (Node sink : node.sinks) {
            if (sink.state != Node.State.DIRTY) {
                sink.state = Node.State.DIRTY;
                if (sink.eager) {
                    cursorSet.add(sink);
                }
                dirtyTheSinks(sink);
            }
        }
    }

    private static void resolveNode(Node node) {
        if (node.state == Node.State.CLEAN) {
            return;
        }

        if (node.sources != null) {
            for (Node source : new HashSet<>(node.sources)) {
                if (source.state == Node.State.DIRTY || source.state == Node.State.STALE) {
                    resolveNode(source);
                }
            }
        }

        if (node.state == Node.State.STALE) {
            node.state = Node.State.CLEAN;
        } else if (node.state == Node.State.DIRTY) {
            boolean changed = false;
            if (node.update != null) {
                cleanupNode(node);
                changed = node.update.get();
            }
            node.state = Node.State.CLEAN;
            if (changed) {
                dirtyTheSinks(node);
            }
        }
    }

    private static void cleanupNode(Node node) {
        Deque<Node> stack = new ArrayDeque<>();
        stack.push(node);

        while (!stack.isEmpty()) {
            Node atNode = stack.pop();

            if (atNode.sources != null) {
                for (Node source : atNode.sources) {
                    if (source.sinks != null) {
                        source.sinks.remove(atNode);
                    }
                }
                atNode.sources.clear();
            }

            if (atNode.cleanups != null) {
                while (!atNode.cleanups.isEmpty()) {
                    atNode.cleanups.pop().run();
                }
                atNode.cleanups.clear();
            }

            if (atNode.children != null) {
                for (Node child : atNode.children) {
                    stack.push(child);
                }
                atNode.children.clear();
            }
        }
    }

    // --- Public API Methods ---

    public static <A> A batch(Supplier<A> k) {
        return transaction(k);
    }

    // Fix: Changed Function to BiFunction
    public static <A> Accessor<A> createMemo(Supplier<A> k, BiFunction<A, A, Boolean> equals) {
        if (owner == null) {
            throw new IllegalStateException("Creating a memo outside owner is not supported.");
        }
        
        // Fix: Use Objects::equals for null-safe comparison
        BiFunction<A, A, Boolean> comparator = (equals == null) ? Objects::equals : equals;
        
        // Fix: Use an AtomicReference to hold a mutable value
        AtomicReference<A> valueRef = new AtomicReference<>(null);
        Node node = new Node(false);
        node.children = new HashSet<>();
        node.cleanups = new ArrayDeque<>();
        node.sources = new HashSet<>();
        node.sinks = new HashSet<>();

        node.update = () -> {
            A oldValue = valueRef.get();
            valueRef.set(useOwnerAndObserver(node, k));
            return !comparator.apply(valueRef.get(), oldValue); // Fix: Corrected logic and usage
        };

        owner.children.add(node);

        return () -> {
            if (observer != null) {
                observer.sources.add(node);
                node.sinks.add(observer);
            }
            resolveNode(node);
            return valueRef.get();
        };
    }

    public static <A> Accessor<A> createMemo(Supplier<A> k) {
        return createMemo(k, null);
    }

    public static void createEffect(Runnable k) {
        if (owner == null) {
            throw new IllegalStateException("Creating an effect outside owner is not supported.");
        }
        Node node = new Node(true);
        node.children = new HashSet<>();
        node.cleanups = new ArrayDeque<>();
        node.sources = new HashSet<>();
        node.sinks = new HashSet<>();
        node.update = () -> {
            useOwnerAndObserver(node, () -> {
                k.run();
                return null;
            });
            return false;
        };

        owner.children.add(node);
        transaction(() -> {
            cursorSet.add(node);
            return null;
        });
    }

    public static void onCleanup(Runnable k) {
        if (owner == null) {
            throw new IllegalStateException("Creating a cleanup outside owner is not supported.");
        }
        if (owner.cleanups == null) {
            owner.cleanups = new ArrayDeque<>();
        }
        owner.cleanups.push(k);
    }

    public static <A> A untrack(Supplier<A> k) {
        return useObserver(null, k);
    }

    public static <A> Signal<A> createSignal() {
        return createSignal(null);
    }

    public static <A> Signal<A> createSignal(A a) {
        // Fix: Use AtomicReference to hold a mutable value
        AtomicReference<A> valueRef = new AtomicReference<>(a);

        Node node = new Node(true);
        node.state = Node.State.CLEAN;
        node.sinks = new HashSet<>();

        Accessor<A> accessor = () -> {
            if (observer != null) {
                observer.sources.add(node);
                node.sinks.add(observer);
            }
            return valueRef.get();
        };

        Setter<A> setter = x -> transaction(() -> {
            valueRef.set(x);
            dirtyTheSinks(node);
            return null;
        });

        return new Signal<>(accessor, setter);
    }

    public static <A> A createRoot(Function<Runnable, A> k) {
        Node node = new Node(true);
        node.children = new HashSet<>();
        node.cleanups = new ArrayDeque<>();
        Runnable dispose = () -> cleanupNode(node);
        return useOwner(node, () -> k.apply(dispose));
    }
}
