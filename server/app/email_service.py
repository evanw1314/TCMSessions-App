import logging
import smtplib
from email.mime.text import MIMEText

from fastapi import HTTPException, status

from .config import settings

logger = logging.getLogger("smartring.email")


def send_verification_email(to_email: str, code: str) -> None:
    if not (settings.smtp_host and settings.smtp_user and settings.smtp_password and settings.email_from):
        raise HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "Email sending is not configured on the server (SMTP_* / EMAIL_FROM)",
        )
    msg = MIMEText(f"Your verification code is {code}. It expires in 15 minutes.")
    msg["Subject"] = "Your verification code"
    msg["From"] = settings.email_from
    msg["To"] = to_email
    try:
        if settings.smtp_port == 465:
            with smtplib.SMTP_SSL(settings.smtp_host, settings.smtp_port, timeout=10) as s:
                s.login(settings.smtp_user, settings.smtp_password)
                s.send_message(msg)
        else:
            with smtplib.SMTP(settings.smtp_host, settings.smtp_port, timeout=10) as s:
                s.starttls()
                s.login(settings.smtp_user, settings.smtp_password)
                s.send_message(msg)
    except (smtplib.SMTPException, OSError):
        logger.exception("Failed to send verification email to %s", to_email)
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Failed to send verification email")
