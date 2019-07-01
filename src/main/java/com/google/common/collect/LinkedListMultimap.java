/*
 * Copyright (C) 2007 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.common.collect;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkPositionIndex;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.CollectPreconditions.checkRemove;
import static java.util.Collections.unmodifiableList;

import com.google.common.annotations.GwtCompatible;
import com.google.common.annotations.GwtIncompatible;
import com.google.common.annotations.Nullable;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.j2objc.annotations.WeakOuter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.AbstractSequentialList;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Consumer;

/**
 * An implementation of {@code ListMultimap} that supports deterministic iteration order for both
 * keys and values. The iteration order is preserved across non-distinct key values. For example,
 * for the following multimap definition:
 *
 * <pre>{@code
 * Multimap<K, V> multimap = LinkedListMultimap.create();
 * multimap.put(key1, foo);
 * multimap.put(key2, bar);
 * multimap.put(key1, baz);
 * }</pre>
 *
 * ... the iteration order for {@link #keys()} is {@code [key1, key2, key1]}, and similarly for
 * {@link #entries()}. Unlike {@link LinkedHashMultimap}, the iteration order is kept consistent
 * between keys, entries and values. For example, calling:
 *
 * <pre>{@code
 * multimap.remove(key1, foo);
 * }</pre>
 *
 * <p>changes the entries iteration order to {@code [key2=bar, key1=baz]} and the key iteration
 * order to {@code [key2, key1]}. The {@link #entries()} iterator returns mutable map entries, and
 * {@link #replaceValues} attempts to preserve iteration order as much as possible.
 *
 * <p>The collections returned by {@link #keySet()} and {@link #asMap} iterate through the keys in
 * the order they were first added to the multimap. Similarly, {@link #get}, {@link #removeAll}, and
 * {@link #replaceValues} return collections that iterate through the values in the order they were
 * added. The collections generated by {@link #entries()}, {@link #keys()}, and {@link #values}
 * iterate across the key-value mappings in the order they were added to the multimap.
 *
 * <p>The {@link #values()} and {@link #entries()} methods both return a {@code List}, instead of
 * the {@code Collection} specified by the {@link ListMultimap} interface.
 *
 * <p>The methods {@link #get}, {@link #keySet()}, {@link #keys()}, {@link #values}, {@link
 * #entries()}, and {@link #asMap} return collections that are views of the multimap. If the
 * multimap is modified while an iteration over any of those collections is in progress, except
 * through the iterator's methods, the results of the iteration are undefined.
 *
 * <p>Keys and values may be null. All optional multimap methods are supported, and all returned
 * views are modifiable.
 *
 * <p>This class is not threadsafe when any concurrent operations update the multimap. Concurrent
 * read operations will work correctly. To allow concurrent update operations, wrap your multimap
 * with a call to {@link Multimaps#synchronizedListMultimap}.
 *
 * <p>See the Guava User Guide article on <a href=
 * "https://github.com/google/guava/wiki/NewCollectionTypesExplained#multimap"> {@code
 * Multimap}</a>.
 *
 * @author Mike Bostock
 * @since 2.0
 */
@GwtCompatible(serializable = true, emulated = true)
public class LinkedListMultimap<K, V> extends AbstractMultimap<K, V>
    implements ListMultimap<K, V>, Serializable {
  /*
   * Order is maintained using a linked list containing all key-value pairs. In
   * addition, a series of disjoint linked lists of "siblings", each containing
   * the values for a specific key, is used to implement {@link
   * ValueForKeyIterator} in constant time.
   */

  private static final class Node<K, V> extends AbstractMapEntry<K, V> {
    final @Nullable K key;
    @Nullable V value;
    @Nullable Node<K, V> next; // the next node (with any key)
    @Nullable Node<K, V> previous; // the previous node (with any key)
    @Nullable Node<K, V> nextSibling; // the next node with the same key
    @Nullable Node<K, V> previousSibling; // the previous node with the same key

    Node(@Nullable K key, @Nullable V value) {
      this.key = key;
      this.value = value;
    }

    @Override
    public K getKey() {
      return key;
    }

    @Override
    public V getValue() {
      return value;
    }

    @Override
    public V setValue(@Nullable V newValue) {
      V result = value;
      this.value = newValue;
      return result;
    }
  }

  private static class KeyList<K, V> {
    Node<K, V> head;
    Node<K, V> tail;
    int count;

    KeyList(Node<K, V> firstNode) {
      this.head = firstNode;
      this.tail = firstNode;
      firstNode.previousSibling = null;
      firstNode.nextSibling = null;
      this.count = 1;
    }
  }

  private transient @Nullable Node<K, V> head; // the head for all keys
  private transient @Nullable Node<K, V> tail; // the tail for all keys
  private transient Map<K, KeyList<K, V>> keyToKeyList;
  private transient int size;

  /*
   * Tracks modifications to keyToKeyList so that addition or removal of keys invalidates
   * preexisting iterators. This does *not* track simple additions and removals of values
   * that are not the first to be added or last to be removed for their key.
   */
  private transient int modCount;

  /** Creates a new, empty {@code LinkedListMultimap} with the default initial capacity. */
  public static <K, V> LinkedListMultimap<K, V> create() {
    return new LinkedListMultimap<>();
  }

  /**
   * Constructs an empty {@code LinkedListMultimap} with enough capacity to hold the specified
   * number of keys without rehashing.
   *
   * @param expectedKeys the expected number of distinct keys
   * @throws IllegalArgumentException if {@code expectedKeys} is negative
   */
  public static <K, V> LinkedListMultimap<K, V> create(int expectedKeys) {
    return new LinkedListMultimap<>(expectedKeys);
  }

  /**
   * Constructs a {@code LinkedListMultimap} with the same mappings as the specified {@code
   * Multimap}. The new multimap has the same {@link Multimap#entries()} iteration order as the
   * input multimap.
   *
   * @param multimap the multimap whose contents are copied to this multimap
   */
  public static <K, V> LinkedListMultimap<K, V> create(
      Multimap<? extends K, ? extends V> multimap) {
    return new LinkedListMultimap<>(multimap);
  }

  LinkedListMultimap() {
    this(12);
  }

  private LinkedListMultimap(int expectedKeys) {
    keyToKeyList = Platform.newHashMapWithExpectedSize(expectedKeys);
  }

  private LinkedListMultimap(Multimap<? extends K, ? extends V> multimap) {
    this(multimap.keySet().size());
    putAll(multimap);
  }

  /**
   * Adds a new node for the specified key-value pair before the specified {@code nextSibling}
   * element, or at the end of the list if {@code nextSibling} is null. Note: if {@code nextSibling}
   * is specified, it MUST be for an node for the same {@code key}!
   */
  @CanIgnoreReturnValue
  private Node<K, V> addNode(@Nullable K key, @Nullable V value, @Nullable Node<K, V> nextSibling) {
    Node<K, V> node = new Node<>(key, value);
    if (head == null) { // empty list
      head = tail = node;
      keyToKeyList.put(key, new KeyList<K, V>(node));
      modCount++;
    } else if (nextSibling == null) { // non-empty list, add to tail
      tail.next = node;
      node.previous = tail;
      tail = node;
      KeyList<K, V> keyList = keyToKeyList.get(key);
      if (keyList == null) {
        keyToKeyList.put(key, keyList = new KeyList<>(node));
        modCount++;
      } else {
        keyList.count++;
        Node<K, V> keyTail = keyList.tail;
        keyTail.nextSibling = node;
        node.previousSibling = keyTail;
        keyList.tail = node;
      }
    } else { // non-empty list, insert before nextSibling
      KeyList<K, V> keyList = keyToKeyList.get(key);
      keyList.count++;
      node.previous = nextSibling.previous;
      node.previousSibling = nextSibling.previousSibling;
      node.next = nextSibling;
      node.nextSibling = nextSibling;
      if (nextSibling.previousSibling == null) { // nextSibling was key head
        keyToKeyList.get(key).head = node;
      } else {
        nextSibling.previousSibling.nextSibling = node;
      }
      if (nextSibling.previous == null) { // nextSibling was head
        head = node;
      } else {
        nextSibling.previous.next = node;
      }
      nextSibling.previous = node;
      nextSibling.previousSibling = node;
    }
    size++;
    return node;
  }

  /**
   * Removes the specified node from the linked list. This method is only intended to be used from
   * the {@code Iterator} classes. See also {@link LinkedListMultimap#removeAllNodes(Object)}.
   */
  private void removeNode(Node<K, V> node) {
    if (node.previous != null) {
      node.previous.next = node.next;
    } else { // node was head
      head = node.next;
    }
    if (node.next != null) {
      node.next.previous = node.previous;
    } else { // node was tail
      tail = node.previous;
    }
    if (node.previousSibling == null && node.nextSibling == null) {
      KeyList<K, V> keyList = keyToKeyList.remove(node.key);
      keyList.count = 0;
      modCount++;
    } else {
      KeyList<K, V> keyList = keyToKeyList.get(node.key);
      keyList.count--;

      if (node.previousSibling == null) {
        keyList.head = node.nextSibling;
      } else {
        node.previousSibling.nextSibling = node.nextSibling;
      }

      if (node.nextSibling == null) {
        keyList.tail = node.previousSibling;
      } else {
        node.nextSibling.previousSibling = node.previousSibling;
      }
    }
    size--;
  }

  /** Removes all nodes for the specified key. */
  private void removeAllNodes(@Nullable Object key) {
    Iterators.clear(new ValueForKeyIterator(key));
  }

  /** Helper method for verifying that an iterator element is present. */
  private static void checkElement(@Nullable Object node) {
    if (node == null) {
      throw new NoSuchElementException();
    }
  }

  /** An {@code Iterator} over all nodes. */
  private class NodeIterator implements ListIterator<Entry<K, V>> {
    int nextIndex;
    @Nullable Node<K, V> next;
    @Nullable Node<K, V> current;
    @Nullable Node<K, V> previous;
    int expectedModCount = modCount;

    NodeIterator(int index) {
      int size = size();
      checkPositionIndex(index, size);
      if (index >= (size / 2)) {
        previous = tail;
        nextIndex = size;
        while (index++ < size) {
          previous();
        }
      } else {
        next = head;
        while (index-- > 0) {
          next();
        }
      }
      current = null;
    }

    private void checkForConcurrentModification() {
      if (modCount != expectedModCount) {
        throw new ConcurrentModificationException();
      }
    }

    @Override
    public boolean hasNext() {
      checkForConcurrentModification();
      return next != null;
    }

    @CanIgnoreReturnValue
    @Override
    public Node<K, V> next() {
      checkForConcurrentModification();
      checkElement(next);
      previous = current = next;
      next = next.next;
      nextIndex++;
      return current;
    }

    @Override
    public void remove() {
      checkForConcurrentModification();
      checkRemove(current != null);
      if (current != next) { // after call to next()
        previous = current.previous;
        nextIndex--;
      } else { // after call to previous()
        next = current.next;
      }
      removeNode(current);
      current = null;
      expectedModCount = modCount;
    }

    @Override
    public boolean hasPrevious() {
      checkForConcurrentModification();
      return previous != null;
    }

    @CanIgnoreReturnValue
    @Override
    public Node<K, V> previous() {
      checkForConcurrentModification();
      checkElement(previous);
      next = current = previous;
      previous = previous.previous;
      nextIndex--;
      return current;
    }

    @Override
    public int nextIndex() {
      return nextIndex;
    }

    @Override
    public int previousIndex() {
      return nextIndex - 1;
    }

    @Override
    public void set(Entry<K, V> e) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void add(Entry<K, V> e) {
      throw new UnsupportedOperationException();
    }

    void setValue(V value) {
      checkState(current != null);
      current.value = value;
    }
  }

  /** An {@code Iterator} over distinct keys in key head order. */
  private class DistinctKeyIterator implements Iterator<K> {
    final Set<K> seenKeys = Sets.<K>newHashSetWithExpectedSize(keySet().size());
    Node<K, V> next = head;
    @Nullable Node<K, V> current;
    int expectedModCount = modCount;

    private void checkForConcurrentModification() {
      if (modCount != expectedModCount) {
        throw new ConcurrentModificationException();
      }
    }

    @Override
    public boolean hasNext() {
      checkForConcurrentModification();
      return next != null;
    }

    @Override
    public K next() {
      checkForConcurrentModification();
      checkElement(next);
      current = next;
      seenKeys.add(current.key);
      do { // skip ahead to next unseen key
        next = next.next;
      } while ((next != null) && !seenKeys.add(next.key));
      return current.key;
    }

    @Override
    public void remove() {
      checkForConcurrentModification();
      checkRemove(current != null);
      removeAllNodes(current.key);
      current = null;
      expectedModCount = modCount;
    }
  }

  /** A {@code ListIterator} over values for a specified key. */
  private class ValueForKeyIterator implements ListIterator<V> {
    final @Nullable Object key;
    int nextIndex;
    @Nullable Node<K, V> next;
    @Nullable Node<K, V> current;
    @Nullable Node<K, V> previous;

    /** Constructs a new iterator over all values for the specified key. */
    ValueForKeyIterator(@Nullable Object key) {
      this.key = key;
      KeyList<K, V> keyList = keyToKeyList.get(key);
      next = (keyList == null) ? null : keyList.head;
    }

    /**
     * Constructs a new iterator over all values for the specified key starting at the specified
     * index. This constructor is optimized so that it starts at either the head or the tail,
     * depending on which is closer to the specified index. This allows adds to the tail to be done
     * in constant time.
     *
     * @throws IndexOutOfBoundsException if index is invalid
     */
    public ValueForKeyIterator(@Nullable Object key, int index) {
      KeyList<K, V> keyList = keyToKeyList.get(key);
      int size = (keyList == null) ? 0 : keyList.count;
      checkPositionIndex(index, size);
      if (index >= (size / 2)) {
        previous = (keyList == null) ? null : keyList.tail;
        nextIndex = size;
        while (index++ < size) {
          previous();
        }
      } else {
        next = (keyList == null) ? null : keyList.head;
        while (index-- > 0) {
          next();
        }
      }
      this.key = key;
      current = null;
    }

    @Override
    public boolean hasNext() {
      return next != null;
    }

    @CanIgnoreReturnValue
    @Override
    public V next() {
      checkElement(next);
      previous = current = next;
      next = next.nextSibling;
      nextIndex++;
      return current.value;
    }

    @Override
    public boolean hasPrevious() {
      return previous != null;
    }

    @CanIgnoreReturnValue
    @Override
    public V previous() {
      checkElement(previous);
      next = current = previous;
      previous = previous.previousSibling;
      nextIndex--;
      return current.value;
    }

    @Override
    public int nextIndex() {
      return nextIndex;
    }

    @Override
    public int previousIndex() {
      return nextIndex - 1;
    }

    @Override
    public void remove() {
      checkRemove(current != null);
      if (current != next) { // after call to next()
        previous = current.previousSibling;
        nextIndex--;
      } else { // after call to previous()
        next = current.nextSibling;
      }
      removeNode(current);
      current = null;
    }

    @Override
    public void set(V value) {
      checkState(current != null);
      current.value = value;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void add(V value) {
      previous = addNode((K) key, value, next);
      nextIndex++;
      current = null;
    }
  }

  // Query Operations

  @Override
  public int size() {
    return size;
  }

  @Override
  public boolean isEmpty() {
    return head == null;
  }

  @Override
  public boolean containsKey(@Nullable Object key) {
    return keyToKeyList.containsKey(key);
  }

  @Override
  public boolean containsValue(@Nullable Object value) {
    return values().contains(value);
  }

  // Modification Operations

  /**
   * Stores a key-value pair in the multimap.
   *
   * @param key key to store in the multimap
   * @param value value to store in the multimap
   * @return {@code true} always
   */
  @CanIgnoreReturnValue
  @Override
  public boolean put(@Nullable K key, @Nullable V value) {
    addNode(key, value, null);
    return true;
  }

  // Bulk Operations

  /**
   * {@inheritDoc}
   *
   * <p>If any entries for the specified {@code key} already exist in the multimap, their values are
   * changed in-place without affecting the iteration order.
   *
   * <p>The returned list is immutable and implements {@link java.util.RandomAccess}.
   */
  @CanIgnoreReturnValue
  @Override
  public List<V> replaceValues(@Nullable K key, Iterable<? extends V> values) {
    List<V> oldValues = getCopy(key);
    ListIterator<V> keyValues = new ValueForKeyIterator(key);
    Iterator<? extends V> newValues = values.iterator();

    // Replace existing values, if any.
    while (keyValues.hasNext() && newValues.hasNext()) {
      keyValues.next();
      keyValues.set(newValues.next());
    }

    // Remove remaining old values, if any.
    while (keyValues.hasNext()) {
      keyValues.next();
      keyValues.remove();
    }

    // Add remaining new values, if any.
    while (newValues.hasNext()) {
      keyValues.add(newValues.next());
    }

    return oldValues;
  }

  private List<V> getCopy(@Nullable Object key) {
    return unmodifiableList(Lists.newArrayList(new ValueForKeyIterator(key)));
  }

  /**
   * {@inheritDoc}
   *
   * <p>The returned list is immutable and implements {@link java.util.RandomAccess}.
   */
  @CanIgnoreReturnValue
  @Override
  public List<V> removeAll(@Nullable Object key) {
    List<V> oldValues = getCopy(key);
    removeAllNodes(key);
    return oldValues;
  }

  @Override
  public void clear() {
    head = null;
    tail = null;
    keyToKeyList.clear();
    size = 0;
    modCount++;
  }

  // Views

  /**
   * {@inheritDoc}
   *
   * <p>If the multimap is modified while an iteration over the list is in progress (except through
   * the iterator's own {@code add}, {@code set} or {@code remove} operations) the results of the
   * iteration are undefined.
   *
   * <p>The returned list is not serializable and does not have random access.
   */
  @Override
  public List<V> get(final @Nullable K key) {
    return new AbstractSequentialList<V>() {
      @Override
      public int size() {
        KeyList<K, V> keyList = keyToKeyList.get(key);
        return (keyList == null) ? 0 : keyList.count;
      }

      @Override
      public ListIterator<V> listIterator(int index) {
        return new ValueForKeyIterator(key, index);
      }
    };
  }

  @Override
  Set<K> createKeySet() {
    @WeakOuter
    class KeySetImpl extends Sets.ImprovedAbstractSet<K> {
      @Override
      public int size() {
        return keyToKeyList.size();
      }

      @Override
      public Iterator<K> iterator() {
        return new DistinctKeyIterator();
      }

      @Override
      public boolean contains(Object key) { // for performance
        return containsKey(key);
      }

      @Override
      public boolean remove(Object o) { // for performance
        return !LinkedListMultimap.this.removeAll(o).isEmpty();
      }
    }
    return new KeySetImpl();
  }

  @Override
  Multiset<K> createKeys() {
    return new Multimaps.Keys<K, V>(this);
  }

  /**
   * {@inheritDoc}
   *
   * <p>The iterator generated by the returned collection traverses the values in the order they
   * were added to the multimap. Because the values may have duplicates and follow the insertion
   * ordering, this method returns a {@link List}, instead of the {@link Collection} specified in
   * the {@link ListMultimap} interface.
   */
  @Override
  public List<V> values() {
    return (List<V>) super.values();
  }

  @Override
  List<V> createValues() {
    @WeakOuter
    class ValuesImpl extends AbstractSequentialList<V> {
      @Override
      public int size() {
        return size;
      }

      @Override
      public ListIterator<V> listIterator(int index) {
        final NodeIterator nodeItr = new NodeIterator(index);
        return new TransformedListIterator<Entry<K, V>, V>(nodeItr) {
          @Override
          V transform(Entry<K, V> entry) {
            return entry.getValue();
          }

          @Override
          public void set(V value) {
            nodeItr.setValue(value);
          }
        };
      }
    }
    return new ValuesImpl();
  }

  /**
   * {@inheritDoc}
   *
   * <p>The iterator generated by the returned collection traverses the entries in the order they
   * were added to the multimap. Because the entries may have duplicates and follow the insertion
   * ordering, this method returns a {@link List}, instead of the {@link Collection} specified in
   * the {@link ListMultimap} interface.
   *
   * <p>An entry's {@link Entry#getKey} method always returns the same key, regardless of what
   * happens subsequently. As long as the corresponding key-value mapping is not removed from the
   * multimap, {@link Entry#getValue} returns the value from the multimap, which may change over
   * time, and {@link Entry#setValue} modifies that value. Removing the mapping from the multimap
   * does not alter the value returned by {@code getValue()}, though a subsequent {@code setValue()}
   * call won't update the multimap but will lead to a revised value being returned by {@code
   * getValue()}.
   */
  @Override
  public List<Entry<K, V>> entries() {
    return (List<Entry<K, V>>) super.entries();
  }

  @Override
  List<Entry<K, V>> createEntries() {
    @WeakOuter
    class EntriesImpl extends AbstractSequentialList<Entry<K, V>> {
      @Override
      public int size() {
        return size;
      }

      @Override
      public ListIterator<Entry<K, V>> listIterator(int index) {
        return new NodeIterator(index);
      }

      @Override
      public void forEach(Consumer<? super Entry<K, V>> action) {
        checkNotNull(action);
        for (Node<K, V> node = head; node != null; node = node.next) {
          action.accept(node);
        }
      }
    }
    return new EntriesImpl();
  }

  @Override
  Iterator<Entry<K, V>> entryIterator() {
    throw new AssertionError("should never be called");
  }

  @Override
  Map<K, Collection<V>> createAsMap() {
    return new Multimaps.AsMap<>(this);
  }

  /**
   * @serialData the number of distinct keys, and then for each distinct key: the first key, the
   *     number of values for that key, and the key's values, followed by successive keys and values
   *     from the entries() ordering
   */
  @GwtIncompatible // java.io.ObjectOutputStream
  private void writeObject(ObjectOutputStream stream) throws IOException {
    stream.defaultWriteObject();
    stream.writeInt(size());
    for (Entry<K, V> entry : entries()) {
      stream.writeObject(entry.getKey());
      stream.writeObject(entry.getValue());
    }
  }

  @GwtIncompatible // java.io.ObjectInputStream
  private void readObject(ObjectInputStream stream) throws IOException, ClassNotFoundException {
    stream.defaultReadObject();
    keyToKeyList = Maps.newLinkedHashMap();
    int size = stream.readInt();
    for (int i = 0; i < size; i++) {
      @SuppressWarnings("unchecked") // reading data stored by writeObject
      K key = (K) stream.readObject();
      @SuppressWarnings("unchecked") // reading data stored by writeObject
      V value = (V) stream.readObject();
      put(key, value);
    }
  }

  @GwtIncompatible // java serialization not supported
  private static final long serialVersionUID = 0;
}
