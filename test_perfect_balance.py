import numpy as np
from PIL import Image, ImageDraw

img = Image.open('/opt/data/cache/images/img_cc3dd75bf56e.jpg').convert('L')
arr = np.array(img)[:950, :]

# High quality anti-aliased mask
# White = 255 -> 0, Dark <= 120 -> 255
alpha = np.clip((220.0 - arr) / (220.0 - 110.0) * 255.0, 0, 255).astype(np.uint8)

# Artwork bounds
mask = alpha > 10
y_idx, x_idx = np.where(mask)
ymin, ymax = y_idx.min(), y_idx.max()
xmin, xmax = x_idx.min(), x_idx.max()

crop_a = alpha[ymin:ymax+1, xmin:xmax+1]
ch, cw = crop_a.shape

# Deep vibrant red
RED = (229, 30, 45) # #E51E2D

def render_perfect(bg_shape='squircle'):
    size = 512
    img_out = Image.new('RGBA', (size, size), (255, 255, 255, 255))
    draw = ImageDraw.Draw(img_out)
    pad = 20
    
    if bg_shape == 'squircle':
        draw.rounded_rectangle([pad, pad, size-pad, size-pad], radius=110, fill=(255, 255, 255, 255), outline=(220, 224, 230, 255), width=3)
    elif bg_shape == 'circle':
        draw.ellipse([pad, pad, size-pad, size-pad], fill=(255, 255, 255, 255), outline=(220, 224, 230, 255), width=3)

    art_rgba = np.zeros((ch, cw, 4), dtype=np.uint8)
    art_rgba[:, :, 0] = RED[0]
    art_rgba[:, :, 1] = RED[1]
    art_rgba[:, :, 2] = RED[2]
    art_rgba[:, :, 3] = crop_a
    art = Image.fromarray(art_rgba, 'RGBA')
    
    # Scale to 310 px disc diameter (art fits with beautiful breathing margins)
    scale = 310.0 / 638.0
    nw, nh = int(cw * scale), int(ch * scale)
    res = art.resize((nw, nh), Image.Resampling.LANCZOS)
    
    # In original: disc center is at (500, 582). In crop: (500 - xmin, 582 - ymin)
    disc_cx = (500 - xmin) * scale
    disc_cy = (582 - ymin) * scale
    
    # Target disc position: X=248, Y=272 (subtly shifted 8px left and 16px down from canvas center 256, 256)
    ox = int(248 - disc_cx)
    oy = int(272 - disc_cy)
    img_out.paste(res, (ox, oy), res)
    return img_out

render_perfect('squircle').save('preview_perfect_squircle.png')
render_perfect('circle').save('preview_perfect_circle.png')
print("Generated preview_perfect_squircle.png and preview_perfect_circle.png")
