import { expect, test } from "@playwright/test";

const API_URL = process.env.API_URL ?? "http://localhost:8080";

/** M4-1 ~ M4-3: 회차 생성 → 대기열 → 입장 → 2석 hold → Fake PG → CONFIRMED. */
test("M4 대기열에서 결제 완료까지 한 번에", async ({ page, request }) => {
  const now = Date.now();
  const created = await request.post(`${API_URL}/api/admin/showtimes`, {
    data: { openAt: new Date(now).toISOString(), startsAt: new Date(now + 7 * 24 * 3600 * 1000).toISOString() },
  });
  expect(created.status()).toBe(201);
  const { showtimeId } = (await created.json()) as { showtimeId: number };

  await page.goto(`/showtimes/${showtimeId}/queue`);
  await page.getByRole("button", { name: "줄 서기" }).click();
  await expect(page).toHaveURL(new RegExp(`/showtimes/${showtimeId}/seats$`), { timeout: 15_000 });

  const available = page.locator('rect[data-status="AVAILABLE"]');
  await expect(available.first()).toBeVisible({ timeout: 10_000 });
  await available.nth(0).click();
  await page.locator('rect[data-status="AVAILABLE"]').nth(0).click();
  await expect(page.locator('rect[data-status="SELECTED"]')).toHaveCount(2);
  await expect(page.getByRole("heading", { name: "선택한 좌석 (2/2)" })).toBeVisible();

  await page.getByRole("button", { name: "선점하기" }).click();
  await expect(page).toHaveURL(/\/reservations\/\d+$/, { timeout: 10_000 });
  const state = page.getByTestId("reservation-state");
  await expect(state).toHaveAttribute("data-state", "HOLDING");

  await page.getByRole("button", { name: "결제하기" }).click();
  await expect(page.getByRole("heading", { name: "카드 결제 (Fake PG)" })).toBeVisible();
  await page.getByRole("button", { name: /결제 \(Fake PG\)$/ }).click();

  await expect(state).toHaveAttribute("data-state", "CONFIRMED", { timeout: 20_000 });
  await expect(page.getByText("결제가 승인되었습니다")).toBeVisible();
});
