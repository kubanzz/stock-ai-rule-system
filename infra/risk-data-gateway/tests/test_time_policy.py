from datetime import date, datetime

import pytest

from risk_gateway.time_policy import (
    TIME_POLICY_VERSION,
    align_global_to_a_share,
    dated_value_times,
    exact_event_times,
    market_times,
)


CN_CALENDAR = (date(2026, 7, 17), date(2026, 7, 20), date(2026, 7, 21))


def test_daily_market_value_is_available_after_close():
    point = market_times(date(2026, 7, 17))

    assert point.observed_at.isoformat() == "2026-07-17T15:00:00+08:00"
    assert point.available_at.isoformat() == "2026-07-17T15:30:00+08:00"
    assert point.policy_version == TIME_POLICY_VERSION == "cn-a-pit-v1"


def test_date_only_value_is_available_on_next_open_day():
    point = dated_value_times(date(2026, 7, 17), CN_CALENDAR)

    assert point.observed_at.isoformat() == "2026-07-17T23:59:59+08:00"
    assert point.available_at.isoformat() == "2026-07-20T09:00:00+08:00"


def test_exact_event_keeps_source_timestamp():
    event_at = datetime.fromisoformat("2026-07-17T18:12:30+08:00")

    point = exact_event_times(event_at)

    assert point.observed_at == event_at
    assert point.available_at == event_at


def test_global_close_is_not_assigned_before_it_is_known():
    us_close = datetime.fromisoformat("2026-07-17T16:00:00-04:00")

    aligned = align_global_to_a_share(us_close, CN_CALENDAR)

    assert aligned.trade_date == date(2026, 7, 20)
    assert aligned.observed_at.isoformat() == "2026-07-18T04:00:00+08:00"
    assert aligned.available_at.isoformat() == "2026-07-20T09:00:00+08:00"


def test_global_close_skips_a_share_session_that_already_opened():
    close_after_cn_open = datetime.fromisoformat("2026-07-20T10:00:00+08:00")

    aligned = align_global_to_a_share(close_after_cn_open, CN_CALENDAR)

    assert aligned.trade_date == date(2026, 7, 21)


def test_time_policy_rejects_naive_or_unalignable_timestamps():
    with pytest.raises(ValueError, match="timezone-aware"):
        exact_event_times(datetime(2026, 7, 17, 18, 0))
    with pytest.raises(ValueError, match="later A-share session"):
        align_global_to_a_share(
            datetime.fromisoformat("2026-07-21T10:00:00+08:00"),
            CN_CALENDAR,
        )
