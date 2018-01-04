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
  private double trackingLastPrice;

  BinanceTrader(double tradeDifference, double tradeProfit, int tradeAmount, String baseCurrency, String tradeCurrency, String key, String secret) {
    client = new TradingClient(baseCurrency, tradeCurrency, key, secret);
    trackingLastPrice = client.lastPrice();
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

      logger.info("Base Balance: " + client.getBaseBalance().getFree());

      if (orderId == null) {
        // find a burst to buy
        // but make sure price is ascending!
        if (lastAsk >= profitablePrice) {
          if (lastPrice > trackingLastPrice) {
            logger.info("Buy detected");
            currentlyBoughtPrice = profitablePrice;
            orderId = client.buy(tradeAmount, buyPrice).getOrderId();
            panicBuyCounter = 0;
            panicSellCounter = 0;
          } else {
            logger.warn("woooops, price is falling?!? don`t do something!");
          }
        } else {
          logger.info(String.format("No profit detected, difference %.8f", lastAsk - profitablePrice));
          currentlyBoughtPrice = null;
        }
        trackingLastPrice = lastPrice;
      } else {
        Order order = client.getOrder(orderId);
        OrderStatus status = order.getStatus();
        if (status != OrderStatus.CANCELED) {
          // not new and not canceled, check for profit
          logger.info("Tradingbalance: " + tradingBalance);
          if ("0".equals("" + tradingBalance.getLocked().charAt(0)) && lastAsk >= currentlyBoughtPrice) {
            if (status == OrderStatus.NEW) {
              // nothing happened here, maybe cancel as well?
              panicBuyCounter++;
              logger.info(String.format("order still new, time %d", panicBuyCounter));
              if (panicBuyCounter > 15) {
                client.cancelOrder(orderId);
                clear();
              }
            } else {
              if ("0".equals("" + tradingBalance.getFree().charAt(0))) {
                logger.warn("no balance in trading money, clearing out");
                clear();
              } else if (status == OrderStatus.PARTIALLY_FILLED) {
                logger.info("partially filled - hodl");
              } else if (status == OrderStatus.FILLED) {
                logger.info("Order filled");
                int profitableAsks = 0;
                for(OrderBookEntry entry : orderBook.getAsks()) {
                  double currentAsk = Double.valueOf(entry.getPrice());
                  if (currentAsk >= profitablePrice) {
                    profitableAsks++;
                  }
                }

                if (lastAsk >= profitablePrice && profitableAsks > 5) {
                  logger.info("still gaining profitable profits HODL!!");
                } else {
                  logger.info("Not gaining enough profit anymore, let`s sell");
                  logger.info(String.format("Bought %d for %.8f and sell it for %.8f, this is %.8f coins profit", tradeAmount, sellPrice, currentlyBoughtPrice, (1.0 * currentlyBoughtPrice - sellPrice) * tradeAmount));
                  client.sell(tradeAmount, sellPrice);
                }
              } else {
                // WTF?!
                logger.error("DETECTED WTF!!!!!");
                logger.error("Order: " + order);
                client.panicSell(lastKnownTradingBalance, lastPrice);
                clear();
              }
            }
          } else {
            panicSellCounter++;
            logger.info(String.format("sell request not successful, increasing time %d", panicSellCounter));
            if (panicSellCounter > 160) {
              client.sell(tradeAmount, profitablePrice);
              clear();
            }
          }
        } else {
          logger.warn("Order was canceled, cleaning up.");
          clear(); // Order was canceled, so clear and go on
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
    currentlyBoughtPrice = null;
  }
}
