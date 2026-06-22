from __future__ import annotations

from gpx_link.desktop_ads import (
    AD_FREE_ENV,
    AD_HEIGHT_ENV,
    AD_HTML_ENV,
    AD_HTML_FILE_ENV,
    AD_URL_ENV,
    REMOVE_ADS_URL_ENV,
    load_desktop_ad_config,
)


def test_load_desktop_ad_config_returns_none_when_unconfigured() -> None:
    assert load_desktop_ad_config({}) is None


def test_load_desktop_ad_config_uses_remote_banner_url() -> None:
    cfg = load_desktop_ad_config(
        {
            AD_URL_ENV: "https://ads.example.test/gpx-link/banner.html",
            AD_HEIGHT_ENV: "90",
            REMOVE_ADS_URL_ENV: "https://example.test/gpx-link/remove-ads",
        }
    )

    assert cfg is not None
    assert cfg.source == "url"
    assert cfg.content == "https://ads.example.test/gpx-link/banner.html"
    assert cfg.height == 90
    assert cfg.remove_ads_url == "https://example.test/gpx-link/remove-ads"


def test_load_desktop_ad_config_uses_inline_html() -> None:
    cfg = load_desktop_ad_config(
        {
            AD_HTML_ENV: "<a href='https://example.test'>Sponsored</a>",
            AD_HEIGHT_ENV: "999",
        }
    )

    assert cfg is not None
    assert cfg.source == "html"
    assert cfg.content == "<a href='https://example.test'>Sponsored</a>"
    assert cfg.height == 160


def test_load_desktop_ad_config_reads_html_file(tmp_path) -> None:
    ad_file = tmp_path / "ad.html"
    ad_file.write_text("<p>Desktop banner</p>\n", encoding="utf-8")

    cfg = load_desktop_ad_config({AD_HTML_FILE_ENV: str(ad_file)})

    assert cfg is not None
    assert cfg.source == "html"
    assert cfg.content == "<p>Desktop banner</p>\n"


def test_load_desktop_ad_config_respects_ad_free_switch() -> None:
    cfg = load_desktop_ad_config(
        {
            AD_FREE_ENV: "true",
            AD_URL_ENV: "https://ads.example.test/gpx-link/banner.html",
        }
    )

    assert cfg is None


def test_load_desktop_ad_config_ignores_invalid_urls() -> None:
    cfg = load_desktop_ad_config(
        {
            AD_URL_ENV: "file:///tmp/ad.html",
            REMOVE_ADS_URL_ENV: "javascript:alert(1)",
        }
    )

    assert cfg is None
