## PATCH http://localhost:34799/echo → 200 OK

### Request Body
```json
{
  "email": "alice3@example.org"
}
```

### Response Headers
```
content-length: 71
content-type: application/json
date: Fri, 02 Oct 2026 20:24:05 GMT
```

### Response Body
```json
{
  "method": "PATCH",
  "path": "/echo",
  "body": {
    "email": "alice3@example.org"
  }
}
```

### cURL
```bash
curl 'http://localhost:34799/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
