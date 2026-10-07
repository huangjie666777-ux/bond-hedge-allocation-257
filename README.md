# hedge257

固定收益估值程序的利率曲线与债券定价库（Java 17 + Apache Commons Math 3.6.1，无前端、无 HTTP）。

## 构建与运行

```
make compile   # 编译到 target/classes
make test      # 运行 JUnit Jupiter 自测（src/test/java）
make run       # 运行估值与风险示例 com.hedge257.Main
make clean
```

依赖位于 `third_party/`（见 `dependencies.lock.json`）：Commons Math 3.6.1 与 JUnit Platform
Console Standalone 1.10.3。要求 JDK 17 与 GNU Make。

## 约定

- 所有日期为合同日期，计息方式统一 ACT/365F：`alpha = 实际天数 / 365`。
- 报价带唯一 ID；拒绝 NaN/无穷、非法日期、非正分母等输入。
- 折现曲线在估值日 `D(valueDate) = 1`，所有 `D` 必须为正、有限。
- 时间轴为估值日起的 ACT/365F 年数；相邻节点间对 `log D` 线性插值。
- 曲线区间（含两端）之外的查询一律拒绝。允许负报价与 `D > 1`。

## 曲线引导

输入估值日、存款报价与固定对浮动互换报价（`CurveBootstrapper`）：

- 存款从估值日起息到期：`D = 1 / (1 + r * alpha)`；分母非正有限则失败。
- 互换从估值日起息，付息日严格递增，末年为终点：
  `S * sum(alpha_i * D_i) = 1 - D_T`，`alpha_i` 按相邻付息日（首期为估值日）ACT/365F 计算。
- 按终点升序逐支求解，重复终点拒绝；新段内的付款日以待求终点节点做 log D 线性插值。
- 互换用 Commons Math `BrentSolver` 在 `log D_T` 上求根（根对应严格正的 `D_T`），自动扩展括号。
- `CurveConfig(absoluteTolerance, maxEvaluations, reproduceTolerance)` 可配求根预算与复现容差。
- 每支求解后都重新定价；未达复现容差则整条曲线失败（`BootstrapException`），并返回
  截至该支（含失败支）的逐支复现值与残差。成功结果 `BootstrapResult` 返回曲线与全部复现记录。

## 债券定价

`Bond` 接收发行日、严格递增的付息日（最后一日为到期日）、正面额与非负年票息：

- 计息日与支付日相同，无除息期；每期息票 = 面额 × 票息 × 相邻付息日 ACT/365F，末期返还本金。
- 结算日必须在 `[发行日, 到期日)` 且落在曲线范围内（含端点）。
- 仅保留支付日严格晚于结算日的现金流，以 `D(支付日) / D(结算日)` 折现。
- `BondPricer.price` 返回每百元全价（dirty）、应计利息与净价（clean = dirty - 应计）；
  付息日应计为零。

## DV01 风险

`RiskEngine` 对每条报价分别上、下移 1 bp（默认 0.0001，可配），每次都重新引导整条曲线后
重新定价债券，净价 DV01 = `(P(下移) - P(上移)) / 2`。某个方向引导或定价失败时，该报价结果
`QuoteDv01` 只带失败原因，不写零。

## 发行人提前赎回估值

`CallableBondPricer.price(Bond, 结算日, DiscountCurve, sigma, 等步长天数, List<CallPrice>)`
在原债券现金流定价链上接入重组二叉短率树，量化发行人赎回权成本；不修改任何输入：

- 输入校验：`sigma` 必须有限非负；结算日至到期日天数必须被步长天数整除且不超过 300 步；
  结算日后每个剩余付息日（含到期日）都必须落在网格上；网格末日不得晚于曲线最后节点。
- 树定义：`i` 层 `j` 节点短率 `r = a_i + (2j - i) * sigma * sqrt(dt)`，
  `dt = 步长天数 / 365`，上下风险中性概率各半，折现因子为 `exp(-r*dt)`；允许负利率与 `sigma=0`。
- 逐层校准：由上一层状态价格解析求解 `a_i`，使到达下一网格日的状态价格之和等于
  `D(下一网格日) / D(结算)`；每层的复现残差随结果返回，非有限状态价格/短率抛出
  `TreeCalibrationException`，残差超过 `1e-10` 不交付价格。
- 赎回表 `CallPrice(date, price)`：每百元价格必须为正有限，日期必须严格位于
  `(结算日, 到期日)` 内且是剩余付息日，重复日期拒绝。
- 向后递推：赎回日先支付当期票息，再比较不含当期票息的继续价值与赎回价，发行人取较小者；
  严格大于才行权，等值继续。行权后该节点不再承担未来息本；无赎回时到期付息还本。
- 结果 `CallableBondPrice` 返回每百元可赎回全价、复用原 ACT/365F 规则的应计与净价、
  同一棵树上的无赎回对照全价、二者价差（赎回权成本）、逐层校准残差，以及每个赎回日各节点的
  短率、继续价值（不含当期票息）和行权标记 `CallNodeSnapshot`。
- 无赎回对照树价必须复现 `BondPricer` 的曲线全价（容差 `1e-8`），否则拒绝交付，
  确保树、合同与原现金流定价链一致；不使用静态收益率阈值。

`make run` 在原有存款/互换报价引导出的同一曲线上额外展示一只 6% 可赎回债券：
结算日 2026-10-06、100 天等步长、`sigma=1.20%`、两个赎回日（101.50、100.75），
并打印无赎回曲线价/树价、可赎回全价/净价、赎回权成本、校准残差与各节点行权结果。

## 预算内债券组合对冲

`HedgeService.hedge(HedgeRequest)` 在原曲线、债券与可赎回定价链上求解预算内组合对冲：

- `Holding` 的 `quantity` 是带符号、连续的百元面值数量；负号表示空头。可附
  `List<CallPrice>`、`sigma` 与等步长天数。候选用普通 `Bond` 构造 `Candidate`，
  给定带符号交易量上下限；卖出和买入都允许。
- 输入最多 20 个持仓、12 个候选和 20 条报价。所有报价桶必须有正、有限的 `L`，
  预算 `B` 必须有限非负；数量、上下限及模型价格也要求有限。重复报价 ID、重复
  持仓/候选 ID、缺失限额和 `minQuantity > maxQuantity` 均在定价前拒绝。
- 风险以报价为桶：每条存款或互换分别独立 `-1bp/+1bp`，两个方向都重新引导整条曲线。
  每百元 DV01 为 `(净价(-1bp) - 净价(+1bp)) / 2`；组合暴露 `b` 按带符号数量汇总，
  候选矩阵 `H` 是每百元 DV01。可赎回持仓在基准、下移和上移曲线上分别重新校准短率树
  并重新判断每个赎回节点，不冻结行权策略，也不平移折现率。
- 任一基准或扰动重估失败时抛出 `HedgeValuationException`，其中 `RiskFailure` 同时带
  报价桶 ID、合同/曲线 ID 与原因；不会删桶，也不会把风险填零。
- LP 使用变量 `x+ >= 0`、`x- >= 0`，令 `x = x+ - x-`，目标最小化
  `max_k |b_k + H_k x| / L_k`。约束包含候选上下限、双重绝对值残余不等式和
  `Σ 原每百元全价_i * (x+_i + x-_i) <= B`，因此空头同样占用成交额预算。
  Commons Math 单纯形是全局线性优化；允许空头、重复候选和线性相关候选，同目标解任取。
- `NoFeasibleSolutionException` 转换为 `InfeasibleHedgeException`；其他优化器错误转换为
  `HedgeNumericalException`。求解后按返回数量重新计算残余、最坏标准化残余和成交额，
  并验证上下限与预算；验证失败不返回部分交易。
- `HedgeSolution` 按候选输入顺序返回带符号买卖数量，按报价顺序返回对冲前后 DV01，
  以及最坏标准化残余和全价成交额。

`make run` 的末段使用 10 个百元面值单位的 6% 可赎回持仓和两只普通国债候选，展示
100 元全价成交额预算受限、候选允许双向交易时的对冲结果、逐桶前后 DV01 和实际成交额。

## 包结构

`src/main/java/com/hedge257/`：`DayCount`、`Validate`、`CurveConfig`、`DepositQuote`、
`SwapQuote`、`DiscountCurve`、`CurveBootstrapper`、`BootstrapResult`、`InstrumentRepricing`、
`BootstrapException`、`Bond`、`BondCashflow`、`BondPrice`、`BondPricer`、`QuoteDv01`、
`Dv01Report`、`RiskEngine`、`ShortRateTree`、`TreeCalibrationException`、`CallPrice`、
`CallableBondPrice`、`CallNodeSnapshot`、`CallableBondPricer`、`Holding`、`Candidate`、
`HedgeRequest`、`RiskFailure`、`HedgeValuationException`、`HedgeRiskModel`、
`InfeasibleHedgeException`、`HedgeNumericalException`、`HedgeOptimizer`、`HedgeSolution`、
`HedgeService`、`Main`。
自测：`src/test/java/com/hedge257/Hedge257Test.java`。
