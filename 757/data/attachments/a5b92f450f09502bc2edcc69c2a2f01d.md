## DELETE http://localhost:40189/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Sun, 20 Sep 2026 15:19:31 GMT
```

### Response Body
```json
{
  "method": "DELETE",
  "path": "/echo"
}
```

### cURL
```bash
curl 'http://localhost:40189/echo' \
  -X DELETE
```
