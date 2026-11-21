## PUT http://localhost:33787/echo → 200 OK

### Request Body
```json
{
  "name": "Eve Updated"
}
```

### Response Headers
```
content-length: 61
content-type: application/json
date: Sat, 21 Nov 2026 18:22:30 GMT
```

### Response Body
```json
{
  "method": "PUT",
  "path": "/echo",
  "body": {
    "name": "Eve Updated"
  }
}
```

### cURL
```bash
curl 'http://localhost:33787/echo' \
  -X PUT \
  -d '{
  "name": "Eve Updated"
}'
```
