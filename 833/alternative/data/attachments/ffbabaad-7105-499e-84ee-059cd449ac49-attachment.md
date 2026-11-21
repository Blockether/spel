## PATCH http://localhost:36569/echo → 200 OK

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
date: Sat, 21 Nov 2026 15:30:07 GMT
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
curl 'http://localhost:36569/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
