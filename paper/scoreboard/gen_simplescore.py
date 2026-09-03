# Genere scoreboards.yml SimpleScore : titre gradient anime (phase glissante A18CD1->FBC2EB, meme DA que le TAB)
A=(0xA1,0x8C,0xD1); B=(0xFB,0xC2,0xEB)
TITRE="✦ ᴏᴜᴛᴍɪɴᴅ ᴄᴀꜱɪɴᴏ ✦"
def lerp(t):
    t=max(0,min(1,t))
    return "#%02X%02X%02X"%tuple(round(a+(b-a)*t) for a,b in zip(A,B))
def frame(phase):
    out=""; n=len(TITRE)
    for i,c in enumerate(TITRE):
        if c==" ": out+=" "; continue
        x=(i/(n-1)+phase)%1.0
        t=1-abs(2*x-1)
        out+="&"+lerp(t)+"&l"+c
    return out
def ligne_grad(width=24):
    return "".join("&"+lerp(i/(width-1))+"&m " for i in range(width))
def q(s): return "'"+s.replace("'","''")+"'"
ICO="&#FBC2EB"; LAB="&f&l"; VAL="    &7"
L=["# Scoreboard Outmind Casino (genere par tools/gen_simplescore.py, DA identique au TAB)","outmind:","  titles:"]
for k in range(20):
    L.append("    - text: "+q(frame(k/20))); L.append("      visibleFor: 2")
L.append("  scores:")
scores=[
 (14, ligne_grad()),
 (13, f"  {ICO}☺ {LAB}ɴᴀᴍᴇ"),
 (12, f"{VAL}%player_name%"),
 (11, f"  {ICO}♛ {LAB}ʀᴀɴᴋ"),
 (10, f"    %luckperms_prefix%"),
 (9,  f"  {ICO}⌛ {LAB}ᴘʟᴀʏᴛɪᴍᴇ"),
 (8,  f"{VAL}%outmind_playtime%"),
 (7,  f"  {ICO}⚡ {LAB}ᴘʀᴏꜰɪᴛ"),
 (6,  f"    %outmind_profit_short%"),
 (5,  f"  {ICO}⛃ {LAB}ɪɴᴠᴇꜱᴛᴇᴅ"),
 (4,  f"{VAL}$%outmind_invested_short%"),
 (3,  ligne_grad()),
 (2,  f"  {ICO}✉ {LAB}ᴅɪꜱᴄᴏʀᴅ &8» %outmind_discord%"),
]
for n,t in scores: L.append(f"    {n}: "+q(t))
L+=["  defaultHideNumber: true","  defaultRenderEvery: 20","  conditions: [ ]",""]
open("scoreboards.yml","w").write("\n".join(L))
print("\n".join(L[-17:]))
