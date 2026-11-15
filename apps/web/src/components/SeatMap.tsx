"use client";

import { memo, useMemo } from "react";
import type { SeatView } from "@/lib/api";
import { formatWon } from "@/lib/format";
import { layoutSeatMap, MARGIN, seatLabel, SEAT_SIZE } from "@/lib/seatmap";
import styles from "./SeatMap.module.css";

type Props = {
  seats: readonly SeatView[];
  selected: ReadonlySet<number>;
  onToggle: (seat: SeatView) => void;
};

/** 1,500-seat SVG map (ADR-0008 §1 row 2). One `<rect>` per seat; clicks are delegated to the `<svg>`. */
export const SeatMap = memo(function SeatMap({ seats, selected, onToggle }: Props) {
  const layout = useMemo(() => layoutSeatMap(seats), [seats]);
  const byId = useMemo(() => new Map(seats.map((s) => [s.seatId, s])), [seats]);

  return (
    <div className={styles.wrap}>
      <div className={styles.scroller}>
        <svg
          className={styles.svg}
          viewBox={`0 0 ${layout.width} ${layout.height}`}
          width={layout.width}
          height={layout.height}
          role="group"
          aria-label="좌석맵"
          onClick={(e) => {
            const id = (e.target as Element).getAttribute("data-seat-id");
            const seat = id === null ? undefined : byId.get(Number(id));
            if (seat && (seat.status === "AVAILABLE" || selected.has(seat.seatId))) onToggle(seat);
          }}
        >
          <rect className={styles.stage} x={layout.stage.x} y={layout.stage.y} width={layout.stage.width} height={layout.stage.height} rx={6} />
          <text className={styles.stageText} x={layout.stage.x + layout.stage.width / 2} y={layout.stage.y + layout.stage.height / 2 + 5} textAnchor="middle">
            STAGE
          </text>
          {layout.blocks.map((block) => (
            <g key={block.grade} data-grade={block.grade}>
              <text className={styles.blockLabel} x={MARGIN} y={block.y + 16}>
                {block.grade}석 · {formatWon(block.price)}
              </text>
              {block.rows.map((row) => (
                <g key={row.rowLabel}>
                  <text className={styles.rowLabel} x={MARGIN} y={row.y + SEAT_SIZE - 3}>
                    {row.rowLabel}
                  </text>
                  {row.seats.map(({ seat, x, y }) => {
                    const isSelected = selected.has(seat.seatId);
                    const state = isSelected ? "SELECTED" : seat.status;
                    return (
                      <rect
                        key={seat.seatId}
                        className={`${styles.seat} ${styles[state]}`}
                        x={x}
                        y={y}
                        width={SEAT_SIZE}
                        height={SEAT_SIZE}
                        rx={3}
                        data-seat-id={seat.seatId}
                        data-status={state}
                        aria-label={`${seatLabel(seat)} ${seat.grade} ${state}`}
                      >
                        <title>{`${seatLabel(seat)} · ${seat.grade} · ${formatWon(seat.price)}`}</title>
                      </rect>
                    );
                  })}
                </g>
              ))}
            </g>
          ))}
        </svg>
      </div>
      <ul className={styles.legend}>
        <li>
          <span className={`${styles.swatch} ${styles.AVAILABLE}`} /> 선택 가능
        </li>
        <li>
          <span className={`${styles.swatch} ${styles.SELECTED}`} /> 내 선택
        </li>
        <li>
          <span className={`${styles.swatch} ${styles.HELD}`} /> 선점 중
        </li>
        <li>
          <span className={`${styles.swatch} ${styles.SOLD}`} /> 판매 완료
        </li>
      </ul>
    </div>
  );
});
