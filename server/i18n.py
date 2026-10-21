"""Shared, local-only UI and API-document translations. API fields stay unchanged."""
import json
from pathlib import Path

CATALOG = json.loads((Path(__file__).with_name("static") / "locales.json").read_text(encoding="utf-8"))
SOURCE_KEYS = {text: key for key, text in CATALOG["zh"].items()}
SOURCE_KEYS.update({text: key for key, text in CATALOG["en"].items() if key.startswith("swagger_")})


def language(value, default="en"):
    """Choose a supported language using BCP 47 tags and Accept-Language quality values."""
    choices = []
    for order, item in enumerate((value or "").split(",")):
        tag, *parameters = item.strip().split(";")
        code = tag.lower().replace("_", "-").split("-")[0]
        quality = 1.0
        for parameter in parameters:
            if parameter.strip().startswith("q="):
                try:
                    quality = float(parameter.strip()[2:])
                except ValueError:
                    quality = 0
        if code in CATALOG and 0 < quality <= 1:
            choices.append((-quality, order, code))
    return min(choices)[2] if choices else default


def request_language(request, default="zh"):
    # Preserve the existing admin API's Chinese text for clients without a preference.
    return language(request.headers.get("accept-language"), default)


def translate(text, selected):
    key = SOURCE_KEYS.get(text)
    return CATALOG[selected].get(key, text) if key else text


def schema_translation(value, selected):
    """Return an independent translated schema; never mutate the cached source schema."""
    if isinstance(value, dict):
        return {key: schema_translation(item, selected) for key, item in value.items()}
    if isinstance(value, list):
        return [schema_translation(item, selected) for item in value]
    return translate(value, selected) if isinstance(value, str) else value
