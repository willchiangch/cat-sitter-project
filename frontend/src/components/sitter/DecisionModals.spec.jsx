import React from 'react'
import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QuoteModal } from './DecisionModals'

describe('QuoteModal', () => {
  const defaultProps = {
    isOpen: true,
    onClose: vi.fn(),
    baseAmount: 1000,
    onConfirm: vi.fn()
  }

  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('Rendering', () => {
    it('should return null when isOpen is false', () => {
      const { container } = render(<QuoteModal {...defaultProps} isOpen={false} />)
      expect(container.firstChild).toBeNull()
    })

    it('should render correctly when isOpen is true', () => {
      render(<QuoteModal {...defaultProps} />)
      expect(screen.getByText('專業報價與調價')).toBeInTheDocument()
      
      // Should show the base amount correctly
      const baseAmounts = screen.getAllByText('$1000') // Final and Base might be same initially
      expect(baseAmounts.length).toBeGreaterThan(0)
    })
  })

  describe('User Interactions & Math Calculations', () => {
    it('should calculate final quote correctly when surcharge and discount are applied', () => {
      const { container } = render(<QuoteModal {...defaultProps} />)

      // Get inputs using DOM query since labels are not inherently linked
      const inputs = container.querySelectorAll('input[type="number"]')
      const surchargeInput = inputs[0]
      const discountInput = inputs[1]

      // Act: Simulate typing surcharge and discount
      fireEvent.change(surchargeInput, { target: { value: '200' } })
      fireEvent.change(discountInput, { target: { value: '50' } })

      // Assert: Final total should be dynamically calculated (1000 + 200 - 50 = 1150)
      expect(screen.getByText('$1150')).toBeInTheDocument()
    })

    it('should call onClose when Cancel button is clicked', () => {
      render(<QuoteModal {...defaultProps} />)
      fireEvent.click(screen.getByText(/cancel/i))
      expect(defaultProps.onClose).toHaveBeenCalledTimes(1)
    })

    it('should call onConfirm with correct payload on Confirm & Send', () => {
      const { container } = render(<QuoteModal {...defaultProps} />)

      const surchargeInput = container.querySelectorAll('input[type="number"]')[0]
      const notesInput = screen.getByPlaceholderText(/調價原因標記/i)

      fireEvent.change(surchargeInput, { target: { value: '300' } })
      fireEvent.change(notesInput, { target: { value: 'Holiday weekend' } })

      fireEvent.click(screen.getByText(/confirm & send/i))

      expect(defaultProps.onConfirm).toHaveBeenCalledTimes(1)
      expect(defaultProps.onConfirm).toHaveBeenCalledWith(expect.objectContaining({
        baseAmount: 1000,
        surcharge: '300',
        discount: 0,
        notes: 'Holiday weekend'
      }))
    })
  })
})
