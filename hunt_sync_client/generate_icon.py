"""Build-time icon generation without importing the tray or reading user data."""
from pathlib import Path
from PIL import Image, ImageDraw


def generate():
    folder = Path(__file__).with_name("assets")
    folder.mkdir(exist_ok=True)
    image = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.ellipse((3, 3, 61, 61), fill="#279653")
    draw.ellipse((17, 17, 47, 47), outline="white", width=3)
    draw.line((32, 10, 32, 54), fill="white", width=3)
    draw.line((10, 32, 54, 32), fill="white", width=3)
    image.save(folder / "hunt_tracker.png")
    image.save(folder / "hunt_tracker.ico", sizes=[(16, 16), (32, 32), (48, 48), (64, 64)])


if __name__ == "__main__":
    generate()
