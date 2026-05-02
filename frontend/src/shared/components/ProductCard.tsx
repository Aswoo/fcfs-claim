import React from 'react';
import { View, Text, StyleSheet, TouchableOpacity, Animated } from 'react-native';
import { LinearGradient } from 'expo-linear-gradient';
import { Colors } from '../../constants/colors';
import { Product } from '../../types';
import { StockBar } from './StockBar';
import { StatusBadge } from './StatusBadge';

interface Props {
  product: Product;
  selected: boolean;
  onPress: (product: Product) => void;
}

export const ProductCard = React.memo(({ product, selected, onPress }: Props) => {
  const scaleAnim = React.useRef(new Animated.Value(1)).current;
  const isSoldOut = product.status === 'sold_out';

  const handlePressIn = React.useCallback(() => {
    Animated.timing(scaleAnim, { toValue: 0.97, duration: 150, useNativeDriver: true }).start();
  }, []);

  const handlePressOut = React.useCallback(() => {
    Animated.timing(scaleAnim, { toValue: 1, duration: 150, useNativeDriver: true }).start();
  }, []);

  const handlePress = React.useCallback(() => {
    if (!isSoldOut) onPress(product);
  }, [product, isSoldOut, onPress]);

  return (
    <Animated.View style={{ transform: [{ scale: scaleAnim }] }}>
      <TouchableOpacity
        activeOpacity={1}
        onPress={handlePress}
        onPressIn={handlePressIn}
        onPressOut={handlePressOut}
        style={[
          styles.card,
          selected && styles.cardSelected,
          isSoldOut && styles.cardSoldOut,
        ]}
      >
        <LinearGradient
          colors={[product.imageColor, product.imageColorEnd]}
          style={styles.imageArea}
        />
        <View style={styles.body}>
          <Text style={styles.name}>{product.name}</Text>
          <Text style={styles.desc}>{product.desc}</Text>
          <View style={styles.stockRow}>
            <StockBar stock={product.stock} total={product.total} />
          </View>
          <StatusBadge status={product.status} stock={product.stock} />
        </View>

        {isSoldOut && (
          <View style={styles.soldOutOverlay}>
            <View style={styles.soldOutTag}>
              <Text style={styles.soldOutTagText}>SOLD OUT</Text>
            </View>
          </View>
        )}
      </TouchableOpacity>
    </Animated.View>
  );
});

const styles = StyleSheet.create({
  card: {
    flexDirection: 'row',
    height: 88,
    marginHorizontal: 12,
    marginBottom: 8,
    backgroundColor: Colors.white,
    borderRadius: 16,
    borderWidth: 2.5,
    borderColor: 'transparent',
    overflow: 'hidden',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.06,
    shadowRadius: 8,
    elevation: 3,
  },
  cardSelected: {
    borderColor: Colors.brandGreen,
    shadowColor: Colors.brandGreen,
    shadowOpacity: 0.15,
    shadowRadius: 14,
    shadowOffset: { width: 0, height: 4 },
  },
  cardSoldOut: {
    opacity: 0.7,
  },
  imageArea: {
    width: 88,
    height: 88,
  },
  body: {
    flex: 1,
    padding: 10,
    paddingLeft: 8,
    gap: 4,
  },
  name: {
    fontSize: 12,
    fontWeight: '700',
    color: Colors.textPrimary,
  },
  desc: {
    fontSize: 10,
    fontWeight: '400',
    color: Colors.textMuted,
  },
  stockRow: {
    marginVertical: 2,
  },
  soldOutOverlay: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: 'rgba(255,255,255,0.55)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  soldOutTag: {
    backgroundColor: Colors.red,
    paddingHorizontal: 12,
    paddingVertical: 4,
    borderRadius: 20,
    transform: [{ rotate: '-8deg' }],
    shadowColor: Colors.red,
    shadowOffset: { width: 0, height: 3 },
    shadowOpacity: 0.3,
    shadowRadius: 10,
    elevation: 4,
  },
  soldOutTagText: {
    fontSize: 10,
    fontWeight: '700',
    color: Colors.white,
  },
});
