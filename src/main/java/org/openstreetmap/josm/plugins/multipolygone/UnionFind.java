package org.openstreetmap.josm.plugins.multipolygone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generic Union-Find (disjoint set) data structure with path halving.
 */
class UnionFind<T> {
    private final Map<T, T> parent = new HashMap<>();

    void makeSet(T item) {
        parent.putIfAbsent(item, item);
    }

    T find(T item) {
        T w = item;
        while (!parent.get(w).equals(w)) {
            parent.put(w, parent.get(parent.get(w)));
            w = parent.get(w);
        }
        return w;
    }

    void union(T a, T b) {
        T rootA = find(a);
        T rootB = find(b);
        if (!rootA.equals(rootB)) {
            parent.put(rootA, rootB);
        }
    }

    /**
     * Collects all items into groups by their root representative.
     * @return list of disjoint sets
     */
    List<Set<T>> components() {
        Map<T, Set<T>> componentMap = new HashMap<>();
        for (T item : parent.keySet()) {
            T root = find(item);
            componentMap.computeIfAbsent(root, k -> new HashSet<>()).add(item);
        }
        return new ArrayList<>(componentMap.values());
    }

    /**
     * Collects all items into groups by their root representative, preserving insertion order.
     * @return map from root to ordered list of items
     */
    Map<T, List<T>> componentLists() {
        Map<T, List<T>> componentMap = new HashMap<>();
        for (T item : parent.keySet()) {
            T root = find(item);
            componentMap.computeIfAbsent(root, k -> new ArrayList<>()).add(item);
        }
        return componentMap;
    }
}
