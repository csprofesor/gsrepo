# ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

import requests
from bs4 import BeautifulSoup

mainUrl = "https://dizikorea3.com"
headers = {
    "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36"
}

categories = [
    ("Kore Dizileri",    f"{mainUrl}/kore-dizileri-izle-dq1/sayfa/"),
    ("Çin Dizileri",     f"{mainUrl}/cin-dizileri/sayfa/"),
    ("Japon Dizileri",   f"{mainUrl}/japon-dizileri/sayfa/"),
    ("Tayland Dizileri", f"{mainUrl}/tayland-dizileri/sayfa/"),
    ("Tayvan Dizileri",  f"{mainUrl}/tayvan-dizileri/sayfa/"),
    ("Filipin Dizileri", f"{mainUrl}/filipin-dizileri/sayfa/"),
    ("Filmler",          f"{mainUrl}/filmler/sayfa/"),
    ("Çin Filmleri",     f"{mainUrl}/cin-filmleri/sayfa/"),
    ("Tayland Filmleri", f"{mainUrl}/tayland-filmleri/sayfa/"),
    ("Efsane Diziler",   f"{mainUrl}/efsane-diziler/sayfa/"),
    ("Dizi Arşivi",     f"{mainUrl}/dizi-arsivi/sayfa/"),
]

def to_search_result(card):
    title_el = card.select_one("span.poster-card-title") or card.select_one("div.poster-card-meta") or card.select_one(".title")
    title = title_el.text.strip() if title_el else None
    if not title:
        return None

    href = card.get("href")
    if not href:
        return None
    if not href.startswith("http"):
        href = mainUrl + href

    img_el = card.select_one("div.poster-card-image img") or card.find("img")
    poster = None
    if img_el:
        for attr in ["data-src", "data-lazy-src", "data-original", "src"]:
            val = img_el.get(attr)
            if val and not (val.startswith("data:image") or "placeholder" in val or "blank" in val or "grey" in val or "gray" in val):
                poster = val
                break
        if not poster and img_el.get("srcset"):
            poster = img_el.get("srcset").split(",")[0].strip().split(" ")[0]

    return {"title": title, "href": href, "poster": poster}

print("=== DiziKorea Kategorileri ve Kartları Test Ediliyor ===")
for name, cat_url in categories:
    url = f"{cat_url}1"
    res = requests.get(url, headers=headers)
    if res.status_code != 200:
        print(f"[!] HATA {res.status_code} - {name} ({url})")
        continue
    
    soup = BeautifulSoup(res.text, "html.parser")
    cards = soup.select("a.poster-card")
    results = [to_search_result(c) for c in cards]
    valid_results = [r for r in results if r is not None]
    missing_poster = sum(1 for r in valid_results if not r["poster"])

    print(f"[+] {name:18} | Bulunan Kart: {len(valid_results):2d} | Poster Eksik/Gri: {missing_poster}")

print("\n=== Arama Endpointi Test Ediliyor ===")
search_res = requests.get(f"{mainUrl}/ara?q=love", headers=headers)
if search_res.status_code == 200:
    items = search_res.json().get("items", [])
    print(f"[+] Arama başarılı | Sonuç sayısı: {len(items)}")
else:
    print(f"[!] Arama hatası: {search_res.status_code}")
