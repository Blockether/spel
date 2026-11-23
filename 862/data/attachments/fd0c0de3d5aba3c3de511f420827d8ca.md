## DELETE http://localhost:41623/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Mon, 23 Nov 2026 10:48:49 GMT
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
curl 'http://localhost:41623/echo' \
  -X DELETE
```
