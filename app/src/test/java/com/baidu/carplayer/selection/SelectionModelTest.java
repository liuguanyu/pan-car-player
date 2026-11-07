package com.baidu.carplayer.selection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

public class SelectionModelTest {
    private SelectionModel<Long> model;
    private final AtomicInteger changes = new AtomicInteger();

    @Before
    public void setUp() {
        model = new SelectionModel<>(id -> id);
        model.setItems(Arrays.asList(1L, 2L, 3L, 4L));
        model.addListener(m -> changes.incrementAndGet());
    }

    @Test
    public void toggleUsesStableIds() {
        model.toggle(2L);
        model.setItems(Arrays.asList(4L, 3L, 2L, 1L)); // 重新排序不影响选择
        assertTrue(model.isSelected(2L));
        assertEquals(Collections.singletonList(2L), model.getSelectedItems());
        model.toggle(2L);
        assertFalse(model.isSelected(2L));
        assertEquals(2, changes.get());
    }

    @Test
    public void removedItemsArePrunedFromSelection() {
        model.toggle(1L);
        model.toggle(3L);
        model.setItems(Arrays.asList(3L, 4L));
        assertEquals(1, model.size());
        assertEquals(Collections.singletonList(3L), model.getSelectedItems());
    }

    @Test
    public void leavingSelectionModeClears() {
        model.setSelectionMode(true);
        model.selectAll(null);
        assertEquals(4, model.size());
        model.setSelectionMode(false);
        assertEquals(0, model.size());
    }

    @Test
    public void filteredSelectAllAndCount() {
        SelectionModel.Filter<Long> even = id -> id % 2 == 0;
        model.selectAll(even);
        assertTrue(model.areAllSelected(even));
        assertFalse(model.areAllSelected(null));
        assertEquals(2, model.count(even));
        assertEquals(0, model.count(id -> id % 2 == 1));
        model.deselectAll(even);
        assertEquals(0, model.size());
    }

    @Test
    public void noOpChangesDoNotNotify() {
        model.clear();
        model.setSelectionMode(false);
        model.setItems(Arrays.asList(1L, 2L, 3L, 4L));
        assertEquals(0, changes.get());
    }
}
