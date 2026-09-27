## PUT http://localhost:36027/echo → 200 OK

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
date: Sun, 27 Sep 2026 16:41:10 GMT
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
curl 'http://localhost:36027/echo' \
  -X PUT \
  -d '{
  "name": "Eve Updated"
}'
```
