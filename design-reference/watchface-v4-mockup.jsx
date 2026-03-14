import { useState, useEffect } from "react";

const ZONES = {
  health: { color: "#FF4466", label: "Santé", desc: "FC, Sommeil, Stress" },
  activity: { color: "#44FF88", label: "Activité", desc: "Pas" },
  calories: { color: "#FFAA33", label: "Calories", desc: "Calories brûlées" },
  system: { color: "#66BBFF", label: "Système", desc: "Batterie" },
  time: { color: "#FFFFFF", label: "Heure", desc: "Heure centrale" },
  calendar: { color: "#aaaaaa", label: "Calendrier", desc: "Jour, Semaine" },
  weather: { color: "#AA88FF", label: "Météo", desc: "Temp, Pluie, UV" },
};

export default function WatchFaceMockup() {
  const [time, setTime] = useState(new Date());

  useEffect(() => {
    const t = setInterval(() => setTime(new Date()), 1000);
    return () => clearInterval(t);
  }, []);

  const hours = time.getHours().toString().padStart(2, "0");
  const minutes = time.getMinutes().toString().padStart(2, "0");
  const seconds = time.getSeconds().toString().padStart(2, "0");
  const dayNames = ["D", "L", "M", "M", "J", "V", "S"];
  const monthNames = ["Jan", "Fév", "Mar", "Avr", "Mai", "Jun", "Jul", "Aoû", "Sep", "Oct", "Nov", "Déc"];
  const dayOfWeek = time.getDay();

  const stepsVal = 4634;
  const stepsMax = 10000;
  const stepsRatio = Math.min(stepsVal / stepsMax, 1);

  const calVal = 496;
  const calMax = 800;
  const calRatio = Math.min(calVal / calMax, 1);

  const batteryPct = 38;

  const arcPath = (cx, cy, r, startAngle, endAngle) => {
    const rad = (a) => ((a - 90) * Math.PI) / 180;
    const x1 = cx + r * Math.cos(rad(startAngle));
    const y1 = cy + r * Math.sin(rad(startAngle));
    const x2 = cx + r * Math.cos(rad(endAngle));
    const y2 = cy + r * Math.sin(rad(endAngle));
    const large = endAngle - startAngle > 180 ? 1 : 0;
    return `M ${x1} ${y1} A ${r} ${r} 0 ${large} 1 ${x2} ${y2}`;
  };

  const W = 340;
  const CX = W / 2;
  const CY = W / 2;

  return (
    <div style={{
      minHeight: "100vh",
      background: "#08080d",
      display: "flex",
      flexDirection: "column",
      alignItems: "center",
      justifyContent: "center",
      fontFamily: "'JetBrains Mono', 'SF Mono', monospace",
      gap: 36,
      padding: "40px 20px",
    }}>
      <link href="https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@300;400;500;700;800&family=Orbitron:wght@400;500;700;900&display=swap" rel="stylesheet" />

      <h1 style={{
        fontFamily: "'Orbitron', sans-serif",
        color: "#fff",
        fontSize: 18,
        fontWeight: 500,
        letterSpacing: 4,
        textTransform: "uppercase",
        opacity: 0.6,
        margin: 0,
      }}>Défi Distance — V4</h1>

      <div style={{ display: "flex", gap: 50, flexWrap: "wrap", justifyContent: "center", alignItems: "flex-start" }}>

        {/* ===== WATCH ===== */}
        <div style={{ position: "relative" }}>
          <div style={{
            width: W,
            height: W,
            borderRadius: "50%",
            background: "radial-gradient(circle at 35% 35%, #161625 0%, #0b0b12 70%)",
            border: "3px solid #2a2a35",
            boxShadow: "0 0 50px rgba(0,0,0,0.9), inset 0 0 40px rgba(0,0,0,0.4), 0 0 100px rgba(68,255,136,0.04)",
            position: "relative",
            overflow: "hidden",
          }}>
            <svg width={W} height={W} style={{ position: "absolute", top: 0, left: 0 }}>
              {/* Bezel ticks */}
              {Array.from({ length: 60 }).map((_, i) => {
                const angle = (i * 6 - 90) * Math.PI / 180;
                const isMajor = i % 5 === 0;
                const r1 = isMajor ? 155 : 160;
                const r2 = 165;
                return (
                  <line key={i}
                    x1={CX + r1 * Math.cos(angle)} y1={CY + r1 * Math.sin(angle)}
                    x2={CX + r2 * Math.cos(angle)} y2={CY + r2 * Math.sin(angle)}
                    stroke={isMajor ? "#444" : "#222"} strokeWidth={isMajor ? 1.5 : 0.8}
                  />
                );
              })}

              {/* Steps arc — green (outer) */}
              <path d={arcPath(CX, CY, 148, -135, -135 + 270)}
                fill="none" stroke={ZONES.activity.color} strokeWidth={5} strokeLinecap="round" opacity={0.1}
              />
              <path d={arcPath(CX, CY, 148, -135, -135 + stepsRatio * 270)}
                fill="none" stroke={ZONES.activity.color} strokeWidth={5} strokeLinecap="round" opacity={0.9}
              />

              {/* Calories arc — orange (inner) */}
              <path d={arcPath(CX, CY, 140, -135, -135 + 270)}
                fill="none" stroke={ZONES.calories.color} strokeWidth={4} strokeLinecap="round" opacity={0.1}
              />
              <path d={arcPath(CX, CY, 140, -135, -135 + calRatio * 270)}
                fill="none" stroke={ZONES.calories.color} strokeWidth={4} strokeLinecap="round" opacity={0.9}
              />
            </svg>

            {/* Content — using absolute positioning for precise control */}
            <div style={{
              position: "absolute",
              top: 0, left: 0, right: 0, bottom: 0,
              display: "flex",
              flexDirection: "column",
              alignItems: "center",
            }}>

              {/* === ROW 1: PAS | CAL — pushed well below arcs === */}
              <div style={{
                display: "flex",
                gap: 10,
                alignItems: "center",
                marginTop: 58,
              }}>
                <div style={{ textAlign: "center" }}>
                  <span style={{ color: ZONES.activity.color, fontSize: 8, fontWeight: 700, letterSpacing: 2 }}>PAS</span>
                  <div style={{ fontFamily: "'Orbitron', sans-serif", color: ZONES.activity.color, fontSize: 17, fontWeight: 800 }}>
                    {stepsVal.toLocaleString()}
                    <span style={{ fontSize: 8, opacity: 0.4, fontWeight: 400, fontFamily: "'JetBrains Mono', monospace" }}>/10k</span>
                  </div>
                </div>
                <div style={{ width: 1, height: 22, background: "#2a2a35" }} />
                <div style={{ textAlign: "center" }}>
                  <span style={{ color: ZONES.calories.color, fontSize: 8, fontWeight: 700, letterSpacing: 2 }}>CAL</span>
                  <div style={{ fontFamily: "'Orbitron', sans-serif", color: ZONES.calories.color, fontSize: 17, fontWeight: 800 }}>
                    {calVal}
                    <span style={{ fontSize: 8, opacity: 0.4, fontWeight: 400, fontFamily: "'JetBrains Mono', monospace" }}>/800</span>
                  </div>
                </div>
              </div>

              {/* === ROW 2: Health === */}
              <div style={{
                display: "flex",
                gap: 10,
                alignItems: "center",
                marginTop: 8,
              }}>
                <div style={{ display: "flex", alignItems: "center", gap: 3 }}>
                  <span style={{ fontSize: 10 }}>😴</span>
                  <span style={{ color: ZONES.health.color, fontSize: 12, fontWeight: 600 }}>7h12</span>
                </div>
                <div style={{ width: 1, height: 10, background: "#2a2a35" }} />
                <div style={{ display: "flex", alignItems: "center", gap: 3 }}>
                  <span style={{ fontSize: 10 }}>❤️</span>
                  <span style={{ color: ZONES.health.color, fontSize: 12, fontWeight: 600 }}>77</span>
                </div>
                <div style={{ width: 1, height: 10, background: "#2a2a35" }} />
                <div style={{ display: "flex", alignItems: "center", gap: 3 }}>
                  <span style={{ fontSize: 10 }}>😊</span>
                  <span style={{ color: ZONES.health.color, fontSize: 12, fontWeight: 600 }}>40</span>
                </div>
              </div>

              {/* === ROW 3: TIME — centered vertically === */}
              <div style={{
                display: "flex",
                alignItems: "baseline",
                marginTop: 6,
              }}>
                <span style={{
                  fontFamily: "'Orbitron', sans-serif",
                  fontSize: 50,
                  fontWeight: 900,
                  color: "#fff",
                  letterSpacing: 2,
                  textShadow: "0 0 18px rgba(255,255,255,0.12)",
                }}>{hours}:{minutes}</span>
                <span style={{
                  fontFamily: "'Orbitron', sans-serif",
                  fontSize: 20,
                  fontWeight: 400,
                  color: "rgba(255,255,255,0.35)",
                  marginLeft: 2,
                }}>:{seconds}</span>
              </div>

              {/* === ROW 4: Weather === */}
              <div style={{
                display: "flex",
                gap: 10,
                marginTop: 4,
                alignItems: "center",
              }}>
                <div style={{ display: "flex", alignItems: "center", gap: 3 }}>
                  <span style={{ fontSize: 10 }}>🌙</span>
                  <span style={{ color: ZONES.weather.color, fontSize: 11, fontWeight: 600 }}>14°</span>
                </div>
                <div style={{ width: 1, height: 10, background: "#2a2a35" }} />
                <div style={{ display: "flex", alignItems: "center", gap: 3 }}>
                  <span style={{ fontSize: 10 }}>🌧️</span>
                  <span style={{ color: ZONES.weather.color, fontSize: 11, fontWeight: 600 }}>25%</span>
                </div>
                <div style={{ width: 1, height: 10, background: "#2a2a35" }} />
                <div style={{ display: "flex", alignItems: "center", gap: 3 }}>
                  <span style={{ fontSize: 10 }}>☀️</span>
                  <span style={{ color: ZONES.weather.color, fontSize: 11, fontWeight: 600 }}>UV 4</span>
                </div>
              </div>

              {/* === ROW 5: Calendar week + day === */}
              <div style={{
                display: "flex",
                alignItems: "center",
                gap: 8,
                marginTop: 8,
              }}>
                <div style={{ display: "flex", gap: 3 }}>
                  {dayNames.map((d, i) => (
                    <div key={i} style={{
                      width: 16,
                      height: 16,
                      borderRadius: 3,
                      background: i === dayOfWeek ? ZONES.calendar.color : "rgba(170,170,170,0.1)",
                      display: "flex",
                      alignItems: "center",
                      justifyContent: "center",
                      fontSize: 8,
                      fontWeight: i === dayOfWeek ? 700 : 400,
                      color: i === dayOfWeek ? "#111" : "rgba(170,170,170,0.4)",
                    }}>{d}</div>
                  ))}
                </div>
                <div style={{
                  color: ZONES.calendar.color,
                  fontFamily: "'Orbitron', sans-serif",
                  fontSize: 20,
                  fontWeight: 800,
                }}>{time.getDate()}</div>
                <div style={{
                  color: ZONES.calendar.color,
                  fontSize: 10,
                  opacity: 0.5,
                }}>{monthNames[time.getMonth()]}</div>
              </div>

              {/* === ROW 6: Battery bar + % === */}
              <div style={{
                marginTop: 5,
                display: "flex",
                flexDirection: "column",
                alignItems: "center",
                gap: 3,
                width: "65%",
              }}>
                <div style={{
                  width: "100%",
                  height: 5,
                  borderRadius: 3,
                  background: "rgba(102,187,255,0.12)",
                  overflow: "hidden",
                }}>
                  <div style={{
                    width: `${batteryPct}%`,
                    height: "100%",
                    borderRadius: 3,
                    background: batteryPct > 20 ? ZONES.system.color : "#FF4466",
                    boxShadow: `0 0 8px ${batteryPct > 20 ? ZONES.system.color : "#FF4466"}44`,
                  }} />
                </div>
                <span style={{
                  color: ZONES.system.color,
                  fontSize: 13,
                  fontWeight: 700,
                  fontFamily: "'Orbitron', sans-serif",
                }}>🔋 {batteryPct}%</span>
              </div>
            </div>
          </div>
        </div>

        {/* ===== LEGEND ===== */}
        <div style={{
          display: "flex",
          flexDirection: "column",
          gap: 12,
          minWidth: 280,
        }}>
          <h2 style={{
            fontFamily: "'Orbitron', sans-serif",
            color: "#fff",
            fontSize: 13,
            fontWeight: 500,
            letterSpacing: 2,
            opacity: 0.4,
            margin: "0 0 4px 0",
          }}>CODE COULEUR</h2>

          {Object.entries(ZONES).map(([key, zone]) => (
            <div key={key} style={{
              display: "flex",
              alignItems: "center",
              gap: 12,
              padding: "7px 12px",
              borderRadius: 8,
              background: "rgba(255,255,255,0.02)",
            }}>
              <div style={{
                width: 12,
                height: 12,
                borderRadius: "50%",
                background: zone.color,
                boxShadow: `0 0 8px ${zone.color}33`,
                flexShrink: 0,
              }} />
              <span style={{ color: zone.color, fontSize: 12, fontWeight: 600, minWidth: 75 }}>{zone.label}</span>
              <span style={{ color: "#555", fontSize: 11 }}>{zone.desc}</span>
            </div>
          ))}

          <div style={{
            marginTop: 16,
            padding: 14,
            borderRadius: 10,
            background: "rgba(255,255,255,0.02)",
            border: "1px solid rgba(255,255,255,0.05)",
          }}>
            <div style={{ color: "#666", fontSize: 10, lineHeight: 1.8 }}>
              <strong style={{ color: "#888" }}>V4 — Espacement corrigé :</strong><br />
              • PAS/CAL descendus sous les jauges arc<br />
              • Contenu global décalé vers le bas<br />
              • Espace mieux réparti entre les lignes<br />
              • Plus de superposition avec les arcs
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
