package io.github.unterstein;

import com.binance.api.client.domain.OrderStatus;
import com.binance.api.client.domain.account.AssetBalance;
import com.binance.api.client.domain.account.Order;
import com.binance.api.client.domain.market.OrderBook;

import com.binance.api.client.domain.market.OrderBookEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BinanceTrader {

  private static Logger logger = LoggerFactory.getLogger(BinanceTrader.class);

  private TradingClient client;
  private final double tradeDifference;
  private final double tradeProfit;
  private final int tradeAmount;

  private Double currentlyBoughtPrice;
  private Long orderId;
  private int panicBuyCounter;
  private int panicSellCounter;
  private String tradeCurrency;
  private double trackingLastPrice;
  private Long sellOrderId;

  BinanceTrader(double tradeDifference, double tradeProfit, int tradeAmount, String baseCurrency, String tradeCurrency, String key, String secret) {
    client = new TradingClient(baseCurrency, tradeCurrency, key, secret);
    trackingLastPrice = client.lastPrice();
    this.tradeCurrency = tradeCurrency;
    this.tradeAmount = tradeAmount;
    this.tradeProfit = tradeProfit;
    this.tradeDifference = tradeDifference;
    clear();
  }

  void tick() {
    try {
      OrderBook orderBook = client.getOrderBook(10);
      double lastPrice = client.lastPrice();
      AssetBalance tradingBalance = client.getTradingBalance();
      double lastKnownTradingBalance = client.getAllTradingBalance();
      double lastBid = Double.valueOf(orderBook.getBids().get(0).getPrice());
      double lastAsk = Double.valueOf(orderBook.getAsks().get(0).getPrice());
      double buyPrice = lastBid + tradeDifference;
      double sellPrice = lastAsk - tradeDifference;
      double profitablePrice = buyPrice + (buyPrice * tradeProfit / 100);

      logger.info("Base Balance: " + client.getBaseBalance().getFree() + ". Trading: " + tradeCurrency);


      if (orderId == null && lastAsk >= profitablePrice && lastPrice > trackingLastPrice) {
        logger.info("Buy detected");
        currentlyBoughtPrice = profitablePrice;
        orderId = client.buy(tradeAmount, buyPrice).getOrderId();
        panicBuyCounter = 0;
        panicSellCounter = 0;
        trackingLastPrice = lastPrice;
      }

      // Return if we haven't bough anything...
      if (orderId == null) { return; }

      if (sellOrderId != null) {
        Order sellOrder = client.getOrder(sellOrderId);
        OrderStatus sellStatus = sellOrder.getStatus();
        if (sellStatus == OrderStatus.FILLED) {
          clear();
        } else {
          logger.info("Waiting to sell order " + sellOrderId);
        }
      } else {
        Order buyOrder = client.getOrder(orderId);
        OrderStatus buyStatus = buyOrder.getStatus();

        if (buyStatus == OrderStatus.CANCELED) {
          logger.warn("Order was canceled, cleaning up.");
          clear();
          return;
        }

        if (lastAsk >= currentlyBoughtPrice) {
          if (buyStatus == OrderStatus.NEW) {
            // nothing happened here, maybe cancel as well?
            panicBuyCounter++;
            logger.info(String.format("order still new, time %d", panicBuyCounter));
            if (panicBuyCounter > 10) {
              client.cancelOrder(orderId);
              clear();
            }
          } else {
            if (buyStatus == OrderStatus.PARTIALLY_FILLED) {
              logger.info("partially filled - hodl");
            } else if (buyStatus == OrderStatus.FILLED) {
              logger.info("Order filled");
              int profitableAsks = 0;
              for(OrderBookEntry entry : orderBook.getBids()) {
                double currentAsk = Double.valueOf(entry.getPrice());
                if (currentAsk >= profitablePrice) {
                  profitableAsks++;
                }
              }

              if (lastAsk >= profitablePrice && profitableAsks > 3) {
                logger.info("still gaining profitable profits HODL!! Last ask: " + lastAsk);
              } else {
                logger.info("Not gaining enough profit anymore, let`s sell");
                logger.info(String.format("Bought %d for %.8f and sell it for %.8f, this is %.8f coins profit", tradeAmount, sellPrice, currentlyBoughtPrice, (1.0 * currentlyBoughtPrice - sellPrice) * tradeAmount));
                sellOrderId = client.sell(tradeAmount, sellPrice).getOrderId();
              }
            }
          }
        } else {
          if (buyStatus != OrderStatus.NEW) {
            int trading = Double.valueOf(tradingBalance.getFree()).intValue();
            logger.info("Trading: " + trading + " for : " + profitablePrice);
            sellOrderId = client.sell(trading, profitablePrice).getOrderId();
          }
        }
      }

    } catch (Exception e) {
      logger.error("Unable to perform ticker", e);
    }
  }

  private void clear() {
    panicBuyCounter = 0;
    panicSellCounter = 0;
    orderId = null;
    sellOrderId = null;
    currentlyBoughtPrice = null;
  }
}
