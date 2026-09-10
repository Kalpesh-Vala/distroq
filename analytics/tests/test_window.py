from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

from distroq_analytics.errors import EXIT_INVALID_WINDOW, InvalidWindowError
from distroq_analytics.window import TimeWindow, format_instant, parse_instant


class TestParseInstant:
    def test_utc_z_suffix_is_accepted(self):
        assert parse_instant("2026-09-01T00:00:00Z") == datetime(
            2026, 9, 1, tzinfo=timezone.utc
        )

    def test_explicit_zero_offset_is_accepted(self):
        assert parse_instant("2026-09-01T00:00:00+00:00") == datetime(
            2026, 9, 1, tzinfo=timezone.utc
        )

    def test_non_utc_offset_is_normalized_to_utc(self):
        parsed = parse_instant("2026-09-01T02:00:00+02:00")
        assert parsed == datetime(2026, 9, 1, 0, 0, tzinfo=timezone.utc)
        assert parsed.utcoffset() == timedelta(0)

    def test_negative_offset_is_normalized_to_utc(self):
        assert parse_instant("2026-08-31T20:00:00-04:00") == datetime(
            2026, 9, 1, 0, 0, tzinfo=timezone.utc
        )

    def test_fractional_seconds_survive(self):
        assert parse_instant("2026-09-01T00:00:00.123456Z").microsecond == 123456

    @pytest.mark.parametrize(
        "raw", ["2026-09-01T00:00:00", "2026-09-01 00:00:00", "2026-09-01"]
    )
    def test_naive_timestamp_is_rejected(self, raw):
        with pytest.raises(InvalidWindowError) as excinfo:
            parse_instant(raw)
        assert "offset" in str(excinfo.value)
        assert excinfo.value.exit_code == EXIT_INVALID_WINDOW

    @pytest.mark.parametrize("raw", ["", "   ", "not-a-timestamp", "2026-13-01T00:00:00Z"])
    def test_garbage_is_rejected(self, raw):
        with pytest.raises(InvalidWindowError):
            parse_instant(raw)

    def test_none_is_rejected(self):
        with pytest.raises(InvalidWindowError):
            parse_instant(None)

    def test_format_round_trips_to_z_form(self):
        assert format_instant(parse_instant("2026-09-01T02:00:00+02:00")) == (
            "2026-09-01T00:00:00Z"
        )


class TestWindowValidation:
    def test_start_equal_to_end_is_rejected(self):
        with pytest.raises(InvalidWindowError) as excinfo:
            TimeWindow.parse("2026-09-01T00:00:00Z", "2026-09-01T00:00:00Z")
        assert excinfo.value.exit_code == EXIT_INVALID_WINDOW

    def test_start_after_end_is_rejected(self):
        with pytest.raises(InvalidWindowError):
            TimeWindow.parse("2026-10-01T00:00:00Z", "2026-09-01T00:00:00Z")

    def test_equivalent_instants_in_different_offsets_are_still_empty(self):
        with pytest.raises(InvalidWindowError):
            TimeWindow.parse("2026-09-01T02:00:00+02:00", "2026-09-01T00:00:00Z")

    def test_valid_window_is_accepted(self):
        window = TimeWindow.parse("2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z")
        assert window.start < window.end


class TestHalfOpenSemantics:
    @pytest.fixture
    def window(self):
        return TimeWindow.parse("2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z")

    def test_start_is_included(self, window):
        assert window.contains(parse_instant("2026-09-01T00:00:00Z"))

    def test_end_is_excluded(self, window):
        assert not window.contains(parse_instant("2026-10-01T00:00:00Z"))

    def test_one_microsecond_before_end_is_included(self, window):
        assert window.contains(parse_instant("2026-09-30T23:59:59.999999Z"))

    def test_before_start_is_excluded(self, window):
        assert not window.contains(parse_instant("2026-08-31T23:59:59.999999Z"))

    def test_consecutive_windows_tile_without_overlap(self):
        first = TimeWindow.parse("2026-09-01T00:00:00Z", "2026-09-02T00:00:00Z")
        second = TimeWindow.parse("2026-09-02T00:00:00Z", "2026-09-03T00:00:00Z")
        boundary = parse_instant("2026-09-02T00:00:00Z")
        assert [first.contains(boundary), second.contains(boundary)] == [False, True]

    def test_membership_requires_an_aware_instant(self, window):
        with pytest.raises(InvalidWindowError):
            window.contains(datetime(2026, 9, 15))

    def test_offset_instant_is_compared_as_utc(self, window):
        # 2026-10-01T01:00+02:00 is 2026-09-30T23:00Z, which is inside the window.
        assert window.contains(parse_instant("2026-10-01T01:00:00+02:00"))


class TestRunId:
    def test_is_deterministic_for_the_same_window(self):
        a = TimeWindow.parse("2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z")
        b = TimeWindow.parse("2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z")
        assert a.run_id == b.run_id

    def test_is_offset_independent(self):
        utc = TimeWindow.parse("2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z")
        offset = TimeWindow.parse("2026-09-01T02:00:00+02:00", "2026-10-01T02:00:00+02:00")
        assert utc.run_id == offset.run_id

    def test_differs_between_windows(self):
        a = TimeWindow.parse("2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z")
        b = TimeWindow.parse("2026-09-01T00:00:00Z", "2026-11-01T00:00:00Z")
        assert a.run_id != b.run_id

    def test_is_filesystem_safe(self):
        run_id = TimeWindow.parse("2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z").run_id
        assert run_id == "20260901T000000Z__20261001T000000Z"
        assert not set(run_id) & set(':/\\*?"<>|')


class TestSqlRendering:
    def test_bounds_render_as_utc_literals(self):
        window = TimeWindow.parse("2026-09-01T02:00:00+02:00", "2026-10-01T00:00:00Z")
        assert window.start_sql == "2026-09-01 00:00:00.000000+00"
        assert window.end_sql == "2026-10-01 00:00:00.000000+00"

    def test_rendering_cannot_carry_operator_text(self):
        window = TimeWindow.parse("2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z")
        assert "'" not in window.start_sql and ";" not in window.start_sql
