# 🎨 Art

| File | What it is |
| --- | --- |
| `banner.png` | The Kindred MC logo at the top of the main README: the name in stone block letters. |
| `faces/` | Head icons of the three starter players, cut from their skins. |
| `skins/` | The starter skins of Kindred, Sol and Echo (standard 64×64 Minecraft skins). |
| `make_banner.py` | The script that draws `banner.png` and `faces/`. |

Changed the logo or a starter skin? Redraw the art from the repository root (needs Python 3 and Pillow, `pip install pillow`):

```bash
python3 assets/make_banner.py
```

To dress a player in another skin in game, put the PNG in the server's `config/kindred/skins` folder and use `/aiplayer skin <skin> [player]` or the K menu. The [operator guide](../docs/operator-guide.md) has the details.
