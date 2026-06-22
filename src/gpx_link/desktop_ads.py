from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Literal
from urllib.parse import urlparse

AdSource = Literal["url", "html"]

AD_URL_ENV = "GPX_LINK_DESKTOP_AD_URL"
AD_HTML_ENV = "GPX_LINK_DESKTOP_AD_HTML"
AD_HTML_FILE_ENV = "GPX_LINK_DESKTOP_AD_HTML_FILE"
AD_BASE_URL_ENV = "GPX_LINK_DESKTOP_AD_BASE_URL"
AD_HEIGHT_ENV = "GPX_LINK_DESKTOP_AD_HEIGHT"
AD_FREE_ENV = "GPX_LINK_DESKTOP_AD_FREE"
REMOVE_ADS_URL_ENV = "GPX_LINK_DESKTOP_REMOVE_ADS_URL"

DEFAULT_AD_HEIGHT = 72
MIN_AD_HEIGHT = 48
MAX_AD_HEIGHT = 160


@dataclass(frozen=True)
class DesktopAdConfig:
    source: AdSource
    content: str
    height: int = DEFAULT_AD_HEIGHT
    base_url: str = "https://localhost/"
    remove_ads_url: str | None = None


def load_desktop_ad_config(
    env: Mapping[str, str],
) -> DesktopAdConfig | None:
    """Load desktop ad settings from environment-style values.

    The desktop app cannot use Android's AdMob SDK, so release packaging supplies
    either a remote banner page or an approved HTML ad snippet.
    """
    if _truthy(env.get(AD_FREE_ENV, "")):
        return None

    height = _coerce_height(env.get(AD_HEIGHT_ENV, ""))
    remove_ads_url = _http_url_or_none(env.get(REMOVE_ADS_URL_ENV, ""))

    ad_url = _http_url_or_none(env.get(AD_URL_ENV, ""))
    if ad_url is not None:
        return DesktopAdConfig(
            source="url",
            content=ad_url,
            height=height,
            remove_ads_url=remove_ads_url,
        )

    ad_html = env.get(AD_HTML_ENV, "").strip()
    if not ad_html:
        ad_html_file = env.get(AD_HTML_FILE_ENV, "").strip()
        if ad_html_file:
            try:
                ad_html = Path(ad_html_file).expanduser().read_text(encoding="utf-8")
            except OSError:
                ad_html = ""

    if not ad_html.strip():
        return None

    base_url = _http_url_or_none(env.get(AD_BASE_URL_ENV, "")) or "https://localhost/"
    return DesktopAdConfig(
        source="html",
        content=ad_html,
        height=height,
        base_url=base_url,
        remove_ads_url=remove_ads_url,
    )


def _truthy(value: str) -> bool:
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _coerce_height(value: str) -> int:
    try:
        height = int(value)
    except ValueError:
        return DEFAULT_AD_HEIGHT
    return max(MIN_AD_HEIGHT, min(MAX_AD_HEIGHT, height))


def _http_url_or_none(value: str) -> str | None:
    url = value.strip()
    if not url:
        return None
    parsed = urlparse(url)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        return None
    return url
