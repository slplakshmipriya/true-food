FROM python:3.12-slim
WORKDIR /srv

# torch CPU first (large layer, changes rarely)
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Bake the BERT model into the image so requests never pay a download.
# Override with SPELLCHECK_MODEL at deploy time if needed.
ARG SPELLCHECK_MODEL=distilbert-base-uncased
ENV SPELLCHECK_MODEL=${SPELLCHECK_MODEL}
RUN python -c "from transformers import pipeline; pipeline('fill-mask', model='$SPELLCHECK_MODEL', top_k=5)"

COPY app.py flagged_ingredients.json vocabulary.txt candidate_vocabulary.txt ./
EXPOSE 8080
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8080"]
