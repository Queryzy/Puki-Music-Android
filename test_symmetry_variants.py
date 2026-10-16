import numpy as np
from PIL import Image, ImageDraw

img = Image.open('/opt/data/cache/images/img_cc3dd75bf56e.jpg').convert('L')
arr = np.array(img)[:950, :]

# Clean binarization with smooth anti-aliasing
# Original: background is white (~255), artwork body is dark (< 150), grooves/hole are white (~255)
# Let's map pixels:
# <= 100 -> full artwork (alpha 255)
# >= 220 -> background/grooves (alpha 0)
# 100..220 -> linear ramp for ultra-clean anti-aliasing
alpha_raw = np.clip((220.0 - arr) / (220.0 - 100.0) * 255.0, 0, 255).astype(np.uint8)

# Find artwork bounds based on alpha > 0
mask = alpha_raw > 10
y_idx, x_idx = np.where(mask)
ymin, ymax = y_idx.min(), y_idx.max()
xmin, xmax = x_idx.min(), x_idx.max()

crop_alpha = alpha_raw[ymin:ymax+1, xmin:xmax+1]
h, w = crop_alpha.shape

# Deep vibrant red: Netflix/YouTube red #E50914 or #E53935
RED = (229, 9, 20) # #E50914

def create_icon(crop_a, center_mode='bbox', red_color=RED, bg_shape='squircle'):
    size = 512
    canvas = Image.new('RGBA', (size, size), (255, 255, 255, 0))
    draw = ImageDraw.Draw(canvas)
    pad = 20
    
    if bg_shape == 'squircle':
        # Pure white squircle with crisp subtle border
        draw.rounded_rectangle([pad, pad, size-pad, size-pad], radius=110, fill=(255, 255, 255, 255), outline=(220, 222, 228, 255), width=3)
    elif bg_shape == 'circle':
        draw.ellipse([pad, pad, size-pad, size-pad], fill=(255, 255, 255, 255), outline=(220, 222, 228, 255), width=3)
        
    # Create red art image
    art_rgba = np.zeros((h, w, 4), dtype=np.uint8)
    art_rgba[:, :, 0] = red_color[0]
    art_rgba[:, :, 1] = red_color[1]
    art_rgba[:, :, 2] = red_color[2]
    art_rgba[:, :, 3] = crop_a
    art_img = Image.fromarray(art_rgba, 'RGBA')
    
    # Scale art to fit comfortably inside safe zone (~62% of size)
    target_scale = 320 / max(w, h)
    new_w = int(w * target_scale)
    new_h = int(h * target_scale)
    art_scaled = art_img.resize((new_w, new_h), Image.Resampling.LANCZOS)
    
    if center_mode == 'bbox':
        # Symmetrical placement: centered directly in middle of canvas
        pos_x = (size - new_w) // 2
        pos_y = (size - new_h) // 2
    elif center_mode == 'disc_centered':
        # Disc center in original coords: X=500, Y=582
        disc_cx_in_crop = (500 - xmin) * target_scale
        disc_cy_in_crop = (582 - ymin) * target_scale
        pos_x = int(size / 2 - disc_cx_in_crop)
        pos_y = int(size / 2 - disc_cy_in_crop)
    elif center_mode == 'optical_balance':
        # Between bbox center and disc center (shifted slightly down from bbox)
        pos_x = (size - new_w) // 2
        pos_y = int((size - new_h) // 2 + 15)
        
    canvas.paste(art_scaled, (pos_x, pos_y), art_scaled)
    return canvas

# Save variants
v1 = create_icon(crop_alpha, center_mode='bbox', bg_shape='squircle')
v1.save('var1_boldred_bbox_squircle.png')

v2 = create_icon(crop_alpha, center_mode='disc_centered', bg_shape='squircle')
v2.save('var2_boldred_disc_squircle.png')

v3 = create_icon(crop_alpha, center_mode='optical_balance', bg_shape='squircle')
v3.save('var3_boldred_optical_squircle.png')

v_circle = create_icon(crop_alpha, center_mode='optical_balance', bg_shape='circle')
v_circle.save('var_circle.png')

print("Variants generated successfully.")
