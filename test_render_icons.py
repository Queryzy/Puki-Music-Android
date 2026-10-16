from PIL import Image, ImageDraw, ImageFilter
import numpy as np

# Load original
im = Image.open('/opt/data/cache/images/img_cc3dd75bf56e.jpg').convert('RGBA').crop((0, 0, 1000, 1000))
arr = np.array(im)

# Clean up bottom artifact & binarize
gray = np.array(im.convert('L'))

# Invert so graphic is white on black, or extract transparency
# Background in original is white (gray > 220). Graphic is dark (gray < 80).
# Let's create a transparent foreground: graphic is dark charcoal #222024, background is transparent
mask_fg = (gray < 120).astype(np.uint8) * 255

# Clean anti-aliasing:
fg_rgba = np.zeros((1000, 1000, 4), dtype=np.uint8)
# We can make the graphic pure dark charcoal #1C191E or pure black
fg_rgba[:, :, 0] = 30
fg_rgba[:, :, 1] = 28
fg_rgba[:, :, 2] = 32
fg_rgba[:, :, 3] = (255 - gray) # smooth alpha from luminance!

fg_img = Image.fromarray(fg_rgba, 'RGBA')

# Now let's test 3 styles on a 512x512 canvas:
# Style 1: Clean White Minimalist (original style, like Apple Music / Tidal minimalist badge)
# White rounded squircle/circle, crisp black vinyl record inside.
def render_style_white(size=512):
    canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(canvas)
    
    # White squircle with subtle dark border
    pad = int(size * 0.04)
    radius = int(size * 0.22)
    draw.rounded_rectangle([pad, pad, size - pad, size - pad], radius=radius, fill=(252, 252, 254, 255), outline=(220, 220, 225, 255), width=int(size*0.015))
    
    # Place graphic centered with safe margin
    inner_size = int(size * 0.72)
    # Resize original cropped image
    cropped_src = Image.open('/opt/data/cache/images/img_cc3dd75bf56e.jpg').crop((0, 0, 1000, 1000))
    resized_graphic = cropped_src.resize((inner_size, inner_size), Image.Resampling.LANCZOS)
    
    offset = (size - inner_size) // 2
    canvas.paste(resized_graphic, (offset, offset), resized_graphic.convert('RGBA'))
    return canvas

# Style 2: Nuclear Dark Wine (Neobrutalism Dark Theme)
# Deep obsidian background #141214, with Aurora #C273D6 ring & White/Silver vinyl mark
def render_style_dark(size=512):
    canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(canvas)
    
    pad = int(size * 0.04)
    radius = int(size * 0.22)
    # Dark wine fill with neon Aurora border
    draw.rounded_rectangle([pad, pad, size - pad, size - pad], radius=radius, fill=(20, 18, 20, 255), outline=(194, 115, 214, 200), width=int(size*0.015))
    
    # Invert the original: white vinyl record with dark grooves!
    cropped_src = Image.open('/opt/data/cache/images/img_cc3dd75bf56e.jpg').convert('L').crop((0, 0, 1000, 1000))
    inverted = Image.eval(cropped_src, lambda p: 255 - p)
    
    # Convert inverted to RGBA where white is icon, dark is transparent
    inv_arr = np.array(inverted)
    icon_rgba = np.zeros((1000, 1000, 4), dtype=np.uint8)
    # Color icon in crisp off-white #F2EEF5
    icon_rgba[:, :, 0] = 245
    icon_rgba[:, :, 1] = 240
    icon_rgba[:, :, 2] = 248
    icon_rgba[:, :, 3] = inv_arr
    
    icon_img = Image.fromarray(icon_rgba, 'RGBA')
    inner_size = int(size * 0.72)
    resized_icon = icon_img.resize((inner_size, inner_size), Image.Resampling.LANCZOS)
    
    offset = (size - inner_size) // 2
    canvas.paste(resized_icon, (offset, offset), resized_icon)
    return canvas

s1 = render_style_white(512)
s1.save('/opt/data/projects/nuclear-android/preview_style_white.png')

s2 = render_style_dark(512)
s2.save('/opt/data/projects/nuclear-android/preview_style_dark.png')

print("Rendered preview_style_white.png and preview_style_dark.png")
