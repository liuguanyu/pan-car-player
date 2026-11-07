package com.baidu.carplayer.selection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 页面持有的多选状态：以稳定 ID 记录选择，与列表位置和过滤结果无关。
 * Adapter 只读取它来渲染，不做选择决策。纯 Java，便于单元测试。
 */
public final class SelectionModel<T> {

    /** 从条目提取稳定 ID。 */
    public interface IdOf<T> {
        long idOf(T item);
    }

    public interface Listener {
        void onSelectionChanged(SelectionModel<?> model);
    }

    private final IdOf<T> idOf;
    private final Set<Long> selected = new LinkedHashSet<>();
    private List<T> items = Collections.emptyList();
    private boolean selectionMode;
    private final List<Listener> listeners = new ArrayList<>();

    public SelectionModel(IdOf<T> idOf) {
        this.idOf = idOf;
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** 列表数据变化时调用：剔除已不在列表中的选择。 */
    public void setItems(Collection<T> newItems) {
        items = newItems == null ? Collections.<T>emptyList() : new ArrayList<>(newItems);
        Set<Long> ids = new LinkedHashSet<>();
        for (T item : items) ids.add(idOf.idOf(item));
        if (selected.retainAll(ids)) {
            notifyChanged();
        }
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    /** 退出多选模式时清空选择。 */
    public void setSelectionMode(boolean value) {
        if (selectionMode == value) return;
        selectionMode = value;
        if (!value) selected.clear();
        notifyChanged();
    }

    public boolean isSelected(T item) {
        return selected.contains(idOf.idOf(item));
    }

    public boolean isSelectedId(long id) {
        return selected.contains(id);
    }

    public void toggle(T item) {
        toggleId(idOf.idOf(item));
    }

    public void toggleId(long id) {
        if (!selected.remove(id)) selected.add(id);
        notifyChanged();
    }

    public void setSelected(T item, boolean value) {
        long id = idOf.idOf(item);
        boolean changed = value ? selected.add(id) : selected.remove(id);
        if (changed) notifyChanged();
    }

    /** 选中满足条件的全部条目（filter 为 null 时全选）。 */
    public void selectAll(Filter<T> filter) {
        boolean changed = false;
        for (T item : items) {
            if (filter == null || filter.accept(item)) changed |= selected.add(idOf.idOf(item));
        }
        if (changed) notifyChanged();
    }

    public void deselectAll(Filter<T> filter) {
        boolean changed = false;
        for (T item : items) {
            if (filter == null || filter.accept(item)) changed |= selected.remove(idOf.idOf(item));
        }
        if (changed) notifyChanged();
    }

    /** 满足条件的条目是否全部已选（没有满足条件的条目时返回 true）。 */
    public boolean areAllSelected(Filter<T> filter) {
        for (T item : items) {
            if ((filter == null || filter.accept(item)) && !selected.contains(idOf.idOf(item))) return false;
        }
        return true;
    }

    public void clear() {
        if (selected.isEmpty()) return;
        selected.clear();
        notifyChanged();
    }

    /** 按当前列表顺序返回被选中的条目。 */
    public List<T> getSelectedItems() {
        List<T> result = new ArrayList<>();
        for (T item : items) {
            if (selected.contains(idOf.idOf(item))) result.add(item);
        }
        return result;
    }

    public int count(Filter<T> filter) {
        int n = 0;
        for (T item : items) {
            if (selected.contains(idOf.idOf(item)) && (filter == null || filter.accept(item))) n++;
        }
        return n;
    }

    public int size() {
        return selected.size();
    }

    public interface Filter<T> {
        boolean accept(T item);
    }

    private void notifyChanged() {
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.onSelectionChanged(this);
        }
    }
}
