package com.airang.drive

import org.junit.Test

class DriveLogicTest {
    @Test
    fun arrivalChecklistRequiresAllItemsAndFreshPark() {
    var assertions = 0
    for (mask in 0..7) {
        var state = ArrivalChecklist()
        for (i in 0..2) if ((mask and (1 shl i)) != 0) state = state.toggle(i)
        check(state.checkedCount == Integer.bitCount(mask)); assertions++
        check(state.canComplete(true) == (mask == 7)); assertions++
        check(!state.canComplete(false)); assertions++
        for (i in 0..2) { check(state.toggle(i).toggle(i) == state); assertions++ }
    }
    check(ArrivalChecklist(true, true, true).toggle(1).canComplete(true).not()); assertions++
    check(ArrivalChecklist().toggle(-1) == ArrivalChecklist()); assertions++
    check(!ArrivalChecklist().canComplete(true)); assertions++
    println("ArrivalChecklist: $assertions assertions passed")
    }

    @Test
    fun completionRecordPersistsAndPreparesNextTrip() {
    var count = 0
    fun verify(value: Boolean) { check(value); count++ }
    val initial = CompletionRecord()
    verify(!initial.hasSummary)
    val first = initial.finish(1000L)
    verify(first.hasSummary)
    verify(first.completedAt == 1000L)
    val restored = CompletionRecord(first.completedAt, first.showSummary)
    verify(restored == first && restored.hasSummary)
    val prepared = first.prepareNext()
    verify(!prepared.hasSummary)
    verify(prepared.completedAt == first.completedAt)
    verify(CompletionRecord(prepared.completedAt, prepared.showSummary) == prepared)
    verify(prepared.prepareNext() == prepared)
    val second = prepared.finish(2000L)
    verify(second.hasSummary && second.completedAt == 2000L)
    verify(!CompletionRecord(0L, true).hasSummary)
    verify(!CompletionRecord(-1L, true).hasSummary)
    verify(runCatching { initial.finish(0L) }.isFailure)
    verify(runCatching { initial.finish(-1L) }.isFailure)
    println("CompletionRecord: $count assertions passed")
    }

    @Test
    fun departurePreferencesCoverAllSelections() {
    var count = 0
    fun verify(value: Boolean) { check(value); count++ }
    verify(DeparturePreferences().selectedIndices == listOf(0, 1, 2))
    for (mask in 0..31) {
        val original = DeparturePreferences(mask)
        verify(original.normalized() == original)
        verify(original.selectedIndices.size == Integer.bitCount(mask))
        verify(DeparturePreferences(original.mask).selectedIndices == original.selectedIndices)
        for (i in 0..4) {
            verify(original.toggle(i).selected(i) != original.selected(i))
            verify(original.toggle(i).toggle(i) == original)
        }
        val draft = original.toggle(4)
        verify(original.mask == mask) // cancelling a draft leaves saved value unchanged
        verify(DeparturePreferences(draft.mask) == draft) // saving/reloading selected bits
    }
    verify(DeparturePreferences(0).selectedIndices.isEmpty())
    verify(DeparturePreferences(-1).normalized().mask == 31)
    verify(DeparturePreferences(63).normalized().mask == 31)
    verify(!DeparturePreferences().selected(-1))
    verify(!DeparturePreferences().selected(5))
    verify(DeparturePreferences().toggle(-1) == DeparturePreferences())
    verify(DeparturePreferences().toggle(5) == DeparturePreferences())
    println("DeparturePreferences: $count assertions passed")
    }

    @Test
    fun vehicleLinkStateHandlesTimeoutBoundaryAndRecovery() {
    var count = 0
    fun verify(value: Boolean) { check(value); count++ }
    for (active in listOf(false, true)) for (running in listOf(false, true))
    for (querying in listOf(false, true)) for (known in listOf(false, true))
    for (failed in listOf(false, true)) for (age in listOf(0L, 5999L, 6000L, 10000L)) {
        val state = vehicleLinkState(active, running, querying, known, 1000L, 1000L+age, failed)
        if (active && !running) verify(state == VehicleLinkState.STOPPED)
        else if (failed) verify(state == VehicleLinkState.FAILED)
        else if (known && (!active || age < 6000L)) verify(state == VehicleLinkState.RECEIVING)
        else if (active && age >= 6000L) verify(state == VehicleLinkState.DELAYED)
        else verify(state == if (querying) VehicleLinkState.CHECKING else VehicleLinkState.WAITING)
    }
    verify(vehicleLinkState(false,false,false,false,0L,100L,false) == VehicleLinkState.WAITING)
    verify(vehicleLinkState(true,true,true,false,0L,100L,false) == VehicleLinkState.CHECKING)
    verify(vehicleLinkState(true,true,true,true,1000L,900L,false) == VehicleLinkState.RECEIVING)
    verify(vehicleLinkState(true,true,true,true,1000L,2000L,true) == VehicleLinkState.FAILED)
    verify(vehicleLinkState(true,true,false,true,2000L,2000L,false) == VehicleLinkState.RECEIVING)
    println("VehicleLinkState: $count assertions passed")
    }

}
