## DELETE http://localhost:40663/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Mon, 14 Sep 2026 21:31:26 GMT
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
curl 'http://localhost:40663/echo' \
  -X DELETE
```
